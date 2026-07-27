package com.linroid.ketch.app.android.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Builds the [WebViewClient] for [tab]. Every callback below fires
 * on the main thread per the WebView contract, so writes to [tab]'s
 * `mutableStateOf` fields are safe without any extra locking.
 */
internal fun browserWebViewClient(
  tab: BrowserTab,
  onLaunchExternal: (String) -> Unit,
  onMagnetLink: (String) -> Unit,
  onPageFinished: (url: String, title: String) -> Unit,
  onSslError: (SslErrorHandler, SslError) -> Unit,
): WebViewClient = object : WebViewClient() {

  override fun shouldOverrideUrlLoading(
    view: WebView,
    request: WebResourceRequest,
  ): Boolean {
    val url = request.url.toString()
    return when {
      url.startsWith("http://", true) || url.startsWith("https://", true) -> false
      url.startsWith("magnet:", true) -> {
        onMagnetLink(url)
        true
      }
      else -> {
        onLaunchExternal(url)
        true
      }
    }
  }

  override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
    tab.url = url
    tab.isLoading = true
    tab.loadError = null
    tab.canGoBack = view.canGoBack()
    tab.canGoForward = view.canGoForward()
  }

  override fun onPageFinished(view: WebView, url: String) {
    tab.isLoading = false
    tab.canGoBack = view.canGoBack()
    tab.canGoForward = view.canGoForward()
    val title = view.title.takeUnless { it.isNullOrBlank() } ?: url
    tab.title = title
    if (tab.loadError == null) onPageFinished(url, title)
  }

  override fun onReceivedError(
    view: WebView,
    request: WebResourceRequest,
    error: WebResourceError,
  ) {
    if (request.isForMainFrame) {
      tab.loadError = error.description?.toString() ?: "Failed to load page"
    }
  }

  override fun onReceivedHttpError(
    view: WebView,
    request: WebResourceRequest,
    errorResponse: WebResourceResponse,
  ) {
    if (request.isForMainFrame && errorResponse.statusCode >= 400) {
      tab.loadError = "HTTP ${errorResponse.statusCode}"
    }
  }

  @SuppressLint("WebViewClientOnReceivedSslError")
  override fun onReceivedSslError(
    view: WebView,
    handler: SslErrorHandler,
    error: SslError,
  ) {
    log.w { "SSL error (${error.primaryError}) loading ${tab.url}" }
    onSslError(handler, error)
  }
}

/** Grace period before a never-navigated `onCreateWindow` transport WebView is force-destroyed. */
private const val TRANSPORT_WINDOW_TIMEOUT_MS = 5_000L

/**
 * Builds the [WebChromeClient] for [tab].
 *
 * `target="_blank"` / `window.open()` popups are handled by spinning
 * up a throwaway transport [WebView] that lives only long enough to
 * capture the first URL it is asked to navigate to; that URL is then
 * opened as a real, managed tab via [onOpenInNewTab] and the
 * transport is torn down. This keeps the "one live WebView per
 * active tab" invariant intact even for popups.
 */
internal fun browserWebChromeClient(
  tab: BrowserTab,
  onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
  onOpenInNewTab: (String) -> Unit,
): WebChromeClient = object : WebChromeClient() {

  override fun onProgressChanged(view: WebView, newProgress: Int) {
    tab.progress = newProgress
  }

  override fun onReceivedTitle(view: WebView, title: String?) {
    if (!title.isNullOrBlank()) tab.title = title
  }

  override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
    tab.favicon = icon
  }

  override fun onShowFileChooser(
    view: WebView,
    filePathCallback: ValueCallback<Array<Uri>>,
    fileChooserParams: FileChooserParams,
  ): Boolean = onShowFileChooser(filePathCallback, fileChooserParams)

  override fun onPermissionRequest(request: PermissionRequest) {
    // The host app declares no camera/mic/location permissions, so
    // WebRTC-style requests are always denied outright rather than
    // left to hang with the (silent) default behavior.
    request.deny()
  }

  override fun onCreateWindow(
    view: WebView,
    isDialog: Boolean,
    isUserGesture: Boolean,
    resultMsg: Message,
  ): Boolean {
    val transport = WebView(view.context).apply {
      // Mirrors the opener's JS setting: some popups compute their
      // target URL via script rather than passing one to window.open
      // directly, and need scripting on to ever navigate at all.
      settings.javaScriptEnabled = view.settings.javaScriptEnabled
    }
    val timeoutHandler = Handler(Looper.getMainLooper())
    var destroyed = false
    fun destroyTransport() {
      if (destroyed) return
      destroyed = true
      timeoutHandler.removeCallbacksAndMessages(null)
      transport.stopLoading()
      transport.webViewClient = object : WebViewClient() {}
      transport.post { transport.destroy() }
    }
    transport.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(
        v: WebView,
        request: WebResourceRequest,
      ): Boolean {
        onOpenInNewTab(request.url.toString())
        destroyTransport()
        return true
      }
    }
    // Safety net: a popup that never navigates (e.g. one that only
    // ever does document.write into itself) would otherwise leak this
    // transport WebView, and its native Chromium resources, for as
    // long as the tab stays open.
    timeoutHandler.postDelayed(::destroyTransport, TRANSPORT_WINDOW_TIMEOUT_MS)
    (resultMsg.obj as WebView.WebViewTransport).webView = transport
    resultMsg.sendToTarget()
    return true
  }
}
