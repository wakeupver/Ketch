package com.linroid.ketch.app.android.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Root composable for the in-app browser. Owns tab/navigation state,
 * uses the embedded [com.linroid.ketch.api.KetchApi] instance
 * ([api]) for downloads, and hosts exactly one live [WebView] at a
 * time via [activeWebViewHolder].
 *
 * @param activeWebViewHolder bridges the Compose-managed WebView to
 *   the hosting Activity (back-press handling, process-death saves).
 * @param registerBackAction lets this composable install/uninstall
 *   the Activity's back-press handler as tabs/find-in-page change.
 */
@Composable
internal fun BrowserRoot(
  api: KetchApi,
  historyStore: BrowserHistoryStore,
  initialUrl: String?,
  activeWebViewHolder: MutableStateFlow<WebView?>,
  onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
  registerBackAction: ((() -> Boolean)?) -> Unit,
  onFinish: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val browserState = rememberSaveable(saver = BrowserStateSaver) { BrowserState() }
  val snackbarHostState = remember { SnackbarHostState() }
  var pendingSslError by remember { mutableStateOf<Pair<SslErrorHandler, SslError>?>(null) }
  val liveWebView by activeWebViewHolder.collectAsState()

  LaunchedEffect(Unit) {
    if (browserState.tabs.isEmpty()) browserState.newTab(initialUrl ?: HOME_URL)
    runCatching {
      browserState.history.addAll(historyStore.loadHistory())
      browserState.bookmarks.addAll(historyStore.loadBookmarks())
    }
  }

  DisposableEffect(browserState, activeWebViewHolder) {
    registerBackAction {
      val tab = browserState.activeTab
      val webView = activeWebViewHolder.value
      when {
        browserState.showFindInPage -> {
          browserState.showFindInPage = false
          webView?.clearMatches()
          true
        }
        webView != null && webView.canGoBack() -> {
          webView.goBack()
          true
        }
        browserState.tabs.size > 1 && tab != null -> {
          closeTabWithCleanup(browserState, webView, tab)
          true
        }
        else -> false
      }
    }
    onDispose { registerBackAction(null) }
  }

  fun startDownload(
    url: String,
    destinationName: String? = null,
    headers: Map<String, String> = emptyMap(),
  ) {
    scope.launch {
      runCatching {
        api.download(
          DownloadRequest(
            url = url,
            destination = destinationName?.let(::Destination),
            headers = headers,
          ),
        )
      }.onSuccess {
        snackbarHostState.showSnackbar("Download added")
      }.onFailure { e ->
        log.w(e) { "Failed to start download for $url" }
        snackbarHostState.showSnackbar(e.message ?: "Failed to start download")
      }
    }
  }

  fun recordVisit(url: String, title: String) {
    if (browserState.activeTab?.isIncognito == true) return
    val entry = HistoryEntry(url, title, System.currentTimeMillis())
    browserState.history.add(0, entry)
    while (browserState.history.size > BrowserHistoryStore.MAX_HISTORY_ENTRIES) {
      browserState.history.removeAt(browserState.history.lastIndex)
    }
    scope.launch { runCatching { historyStore.addVisit(entry) } }
  }

  Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      Column {
        BrowserTopBar(
          state = browserState,
          onNavigate = { input ->
            val resolved = resolveNavigationInput(input)
            when {
              resolved.isEmpty() -> Unit
              resolved.startsWith("magnet:", true) -> startDownload(resolved)
              else -> liveWebView?.loadUrl(resolved) ?: browserState.newTab(resolved)
            }
          },
          onToggleBookmark = {
            browserState.activeTab?.let { tab ->
              val url = tab.url
              scope.launch {
                if (browserState.isBookmarked(url)) {
                  runCatching { historyStore.removeBookmark(url) }
                  browserState.bookmarks.removeAll { it.url == url }
                } else {
                  val entry = BookmarkEntry(url, tab.title.ifBlank { url })
                  runCatching { historyStore.addBookmark(entry) }
                  browserState.bookmarks.add(0, entry)
                }
              }
            }
          },
          onOpenTabSwitcher = { browserState.overlay = BrowserOverlay.TabSwitcher },
          onOpenMenu = { browserState.showMenu = true },
        )
        val tab = browserState.activeTab
        if (tab != null && tab.isLoading) {
          LinearProgressIndicator(
            progress = { tab.progress / 100f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
          )
        }
      }
    },
    bottomBar = {
      BrowserBottomBar(
        tab = browserState.activeTab,
        tabCount = browserState.tabs.size,
        onBack = { liveWebView?.goBack() },
        onForward = { liveWebView?.goForward() },
        onReloadOrStop = {
          if (browserState.activeTab?.isLoading == true) {
            liveWebView?.stopLoading()
          } else {
            liveWebView?.reload()
          }
        },
        onNewTab = { browserState.newTab() },
        onOpenTabSwitcher = { browserState.overlay = BrowserOverlay.TabSwitcher },
      )
    },
  ) { padding ->
    Box(modifier = Modifier.padding(padding).fillMaxSize()) {
      val tab = browserState.activeTab
      if (tab != null) {
        key(tab.id) {
          WebViewHost(
            tab = tab,
            javaScriptEnabled = browserState.javaScriptEnabled,
            onCreated = { webView -> activeWebViewHolder.value = webView },
            onReleased = { webView ->
              if (activeWebViewHolder.value === webView) activeWebViewHolder.value = null
            },
            onLaunchExternal = { url -> launchExternalIntent(context, url) },
            onMagnetLink = { url -> startDownload(url) },
            onPageFinished = ::recordVisit,
            onSslError = { handler, error -> pendingSslError = handler to error },
            onShowFileChooser = onShowFileChooser,
            onOpenInNewTab = { url -> browserState.newTab(url) },
            onDownload = { url, userAgent ->
              val cookie = CookieManager.getInstance().getCookie(url)
              startDownload(
                url = url,
                headers = buildMap {
                  put("User-Agent", userAgent)
                  if (!cookie.isNullOrBlank()) put("Cookie", cookie)
                },
              )
            },
            modifier = Modifier.fillMaxSize(),
          )
        }
      }
      if (browserState.showFindInPage) {
        FindInPageBar(
          state = browserState,
          webView = liveWebView,
          modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        )
      }
    }
  }

  when (browserState.overlay) {
    BrowserOverlay.TabSwitcher -> TabSwitcherSheet(
      state = browserState,
      onClose = { browserState.overlay = null },
      onCloseTab = { id ->
        val tab = browserState.tabs.firstOrNull { it.id == id }
        if (tab != null) {
          val webView = if (id == browserState.activeTabId) liveWebView else null
          closeTabWithCleanup(browserState, webView, tab)
        }
      },
    )
    BrowserOverlay.History -> HistorySheet(
      entries = browserState.history,
      onOpen = {
        browserState.newTab(it)
        browserState.overlay = null
      },
      onClear = {
        scope.launch { runCatching { historyStore.clearHistory() } }
        browserState.history.clear()
      },
      onClose = { browserState.overlay = null },
    )
    BrowserOverlay.Bookmarks -> BookmarksSheet(
      entries = browserState.bookmarks,
      onOpen = {
        browserState.newTab(it)
        browserState.overlay = null
      },
      onRemove = { url ->
        scope.launch { runCatching { historyStore.removeBookmark(url) } }
        browserState.bookmarks.removeAll { it.url == url }
      },
      onClose = { browserState.overlay = null },
    )
    BrowserOverlay.PageDownloads -> PageDownloadsSheet(
      resources = browserState.pageResources,
      onDownload = { resource -> startDownload(resource.url, resource.label) },
      onClose = { browserState.overlay = null },
    )
    null -> Unit
  }

  if (browserState.showMenu) {
    BrowserMenuSheet(
      state = browserState,
      onDismiss = { browserState.showMenu = false },
      onNewTab = { browserState.newTab() },
      onNewIncognitoTab = { browserState.newTab(isIncognito = true) },
      onToggleDesktopMode = {
        browserState.activeTab?.let { tab ->
          tab.desktopMode = !tab.desktopMode
          liveWebView?.let { webView ->
            webView.settings.userAgentString = userAgentFor(webView.context, tab.desktopMode)
            webView.reload()
          }
        }
      },
      onToggleJavaScript = { browserState.javaScriptEnabled = !browserState.javaScriptEnabled },
      onFindInPage = { browserState.showFindInPage = true },
      onFindDownloads = {
        scanPageForDownloads(liveWebView, browserState.pageResources) {
          browserState.overlay = BrowserOverlay.PageDownloads
        }
      },
      onOpenHistory = { browserState.overlay = BrowserOverlay.History },
      onOpenBookmarks = { browserState.overlay = BrowserOverlay.Bookmarks },
      onShare = {
        browserState.activeTab?.let { tab ->
          runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
              type = "text/plain"
              putExtra(Intent.EXTRA_TEXT, tab.url)
            }
            context.startActivity(Intent.createChooser(send, null))
          }
        }
      },
      onCloseBrowser = onFinish,
    )
  }

  pendingSslError?.let { (handler, error) ->
    SslWarningDialog(
      error = error,
      onProceed = { handler.proceed(); pendingSslError = null },
      onCancel = { handler.cancel(); pendingSslError = null },
    )
  }
}

/** Closes [tab]; if it's incognito and still live, best-effort wipes its local data first. */
private fun closeTabWithCleanup(state: BrowserState, webView: WebView?, tab: BrowserTab) {
  if (tab.isIncognito && webView != null) {
    runCatching {
      webView.clearHistory()
      webView.clearCache(true)
      webView.clearFormData()
    }
  }
  state.closeTab(tab.id)
}

@Composable
private fun BrowserTopBar(
  state: BrowserState,
  onNavigate: (String) -> Unit,
  onToggleBookmark: () -> Unit,
  onOpenTabSwitcher: () -> Unit,
  onOpenMenu: () -> Unit,
) {
  val tab = state.activeTab
  val keyboardController = LocalSoftwareKeyboardController.current

  LaunchedEffect(tab?.url, state.isAddressBarEditing) {
    if (!state.isAddressBarEditing && tab != null) {
      state.addressBarText = tab.url
    }
  }

  Surface(tonalElevation = 3.dp) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TabCountBadge(count = state.tabs.size, onClick = onOpenTabSwitcher)
      Spacer(modifier = Modifier.width(8.dp))
      OutlinedTextField(
        value = state.addressBarText,
        onValueChange = { state.addressBarText = it },
        modifier = Modifier
          .weight(1f)
          .onFocusChanged { state.isAddressBarEditing = it.isFocused },
        singleLine = true,
        placeholder = { Text("Search or type URL") },
        leadingIcon = if (tab?.loadError != null) {
          { Icon(Icons.Filled.ErrorOutline, contentDescription = null) }
        } else {
          null
        },
        keyboardOptions = KeyboardOptions(
          imeAction = ImeAction.Go,
          keyboardType = KeyboardType.Uri,
        ),
        keyboardActions = KeyboardActions(
          onGo = {
            keyboardController?.hide()
            onNavigate(state.addressBarText)
          },
        ),
        shape = RoundedCornerShape(24.dp),
      )
      IconButton(onClick = onToggleBookmark) {
        val bookmarked = tab != null && state.isBookmarked(tab.url)
        Icon(
          imageVector = if (bookmarked) Icons.Filled.Star else Icons.Filled.StarBorder,
          contentDescription = if (bookmarked) "Remove bookmark" else "Add bookmark",
        )
      }
      IconButton(onClick = onOpenMenu) {
        Icon(Icons.Filled.MoreVert, contentDescription = "Browser menu")
      }
    }
  }
}

@Composable
private fun TabCountBadge(count: Int, onClick: () -> Unit) {
  IconButton(onClick = onClick) {
    Box(
      modifier = Modifier
        .size(24.dp)
        .border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(4.dp)),
      contentAlignment = Alignment.Center,
    ) {
      Text(text = count.toString(), style = MaterialTheme.typography.labelSmall)
    }
  }
}

@Composable
private fun BrowserBottomBar(
  tab: BrowserTab?,
  tabCount: Int,
  onBack: () -> Unit,
  onForward: () -> Unit,
  onReloadOrStop: () -> Unit,
  onNewTab: () -> Unit,
  onOpenTabSwitcher: () -> Unit,
) {
  Surface(tonalElevation = 3.dp) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .padding(horizontal = 4.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.SpaceAround,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack, enabled = tab?.canGoBack == true) {
        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
      }
      IconButton(onClick = onForward, enabled = tab?.canGoForward == true) {
        Icon(Icons.Filled.ArrowForward, contentDescription = "Forward")
      }
      IconButton(onClick = onNewTab) {
        Icon(Icons.Filled.Add, contentDescription = "New tab")
      }
      IconButton(onClick = onReloadOrStop) {
        val loading = tab?.isLoading == true
        Icon(
          imageVector = if (loading) Icons.Filled.Close else Icons.Filled.Refresh,
          contentDescription = if (loading) "Stop" else "Reload",
        )
      }
      TabCountBadge(count = tabCount, onClick = onOpenTabSwitcher)
    }
  }
}

/**
 * Hosts the single live [WebView] for [tab] via [AndroidView]. The
 * factory runs once per tab activation (Compose caches the created
 * View across recompositions); [onRelease] fires the instant this
 * composable leaves composition -- on tab switch or close -- saving
 * navigation history back onto [tab] and fully destroying the
 * WebView so at most one ever exists at a time.
 */
@Composable
private fun WebViewHost(
  tab: BrowserTab,
  javaScriptEnabled: Boolean,
  onCreated: (WebView) -> Unit,
  onReleased: (WebView) -> Unit,
  onLaunchExternal: (String) -> Unit,
  onMagnetLink: (String) -> Unit,
  onPageFinished: (url: String, title: String) -> Unit,
  onSslError: (SslErrorHandler, SslError) -> Unit,
  onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
  onOpenInNewTab: (String) -> Unit,
  onDownload: (url: String, userAgent: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  // Mirrors KetchTheme's own light/dark decision so WebView's forced
  // darkening below only ever engages when the surrounding UI is
  // actually dark, never against a light-themed app/system state.
  val darkTheme = isSystemInDarkTheme()

  AndroidView(
    modifier = modifier,
    factory = { ctx ->
      WebView(ctx).apply {
        configureWebViewSettings(this, javaScriptEnabled, darkTheme)
        settings.userAgentString = userAgentFor(ctx, tab.desktopMode)
        webViewClient = browserWebViewClient(
          tab = tab,
          onLaunchExternal = onLaunchExternal,
          onMagnetLink = onMagnetLink,
          onPageFinished = onPageFinished,
          onSslError = onSslError,
        )
        webChromeClient = browserWebChromeClient(
          tab = tab,
          onShowFileChooser = onShowFileChooser,
          onOpenInNewTab = onOpenInNewTab,
        )
        setDownloadListener { url, userAgent, _, _, _ -> onDownload(url, userAgent) }
        val saved = tab.savedState
        if (saved != null) restoreState(saved) else loadUrl(tab.url)
        onCreated(this)
      }
    },
    update = { webView ->
      webView.settings.javaScriptEnabled = javaScriptEnabled
      applyAlgorithmicDarkening(webView.settings, darkTheme)
    },
    onRelease = { webView ->
      val bundle = Bundle()
      webView.saveState(bundle)
      tab.savedState = bundle
      webView.stopLoading()
      webView.webChromeClient = null
      webView.setDownloadListener(null)
      webView.webViewClient = object : WebViewClient() {}
      (webView.parent as? ViewGroup)?.removeView(webView)
      onReleased(webView)
      webView.destroy()
    },
  )
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureWebViewSettings(webView: WebView, javaScriptEnabled: Boolean, darkTheme: Boolean) {
  val settings = webView.settings
  settings.javaScriptEnabled = javaScriptEnabled
  settings.domStorageEnabled = true
  settings.setSupportMultipleWindows(true)
  settings.javaScriptCanOpenWindowsAutomatically = true
  settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
  settings.allowFileAccess = false
  settings.allowContentAccess = false
  settings.loadWithOverviewMode = true
  settings.useWideViewPort = true
  settings.builtInZoomControls = true
  settings.displayZoomControls = false
  settings.cacheMode = WebSettings.LOAD_DEFAULT

  if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
    WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
  }
  applyAlgorithmicDarkening(settings, darkTheme)
  // setAcceptCookie is a process-wide CookieManager flag already set once in
  // BrowserActivity.onCreate; repeating it per WebView (this fn runs on every
  // new tab) is redundant work with zero added effect.
  CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
}

/**
 * Applies WebView's forced/algorithmic darkening only when [darkTheme]
 * is true. Enabling it unconditionally is the classic WebView pitfall
 * that breaks rendering across many sites: the engine algorithmically
 * inverts colors on any page lacking its own `prefers-color-scheme`
 * support -- most of the web -- producing washed-out or illegible
 * pages even while the app itself is in light mode.
 */
private fun applyAlgorithmicDarkening(settings: WebSettings, darkTheme: Boolean) {
  if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
    WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, darkTheme)
  }
}

@Composable
private fun FindInPageBar(state: BrowserState, webView: WebView?, modifier: Modifier = Modifier) {
  DisposableEffect(webView) {
    webView?.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
      if (isDoneCounting) {
        state.findTotalMatches = numberOfMatches
        state.findActiveMatch = if (numberOfMatches == 0) 0 else activeMatchOrdinal + 1
      }
    }
    onDispose { webView?.setFindListener(null) }
  }

  Surface(modifier = modifier, tonalElevation = 4.dp) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OutlinedTextField(
        value = state.findQuery,
        onValueChange = { query ->
          state.findQuery = query
          if (query.isEmpty()) webView?.clearMatches() else webView?.findAllAsync(query)
        },
        modifier = Modifier.weight(1f),
        singleLine = true,
        placeholder = { Text("Find in page") },
      )
      Text(
        text = "${state.findActiveMatch}/${state.findTotalMatches}",
        modifier = Modifier.padding(horizontal = 8.dp),
      )
      IconButton(onClick = { webView?.findNext(false) }) {
        Icon(Icons.Filled.ArrowBack, contentDescription = "Previous match")
      }
      IconButton(onClick = { webView?.findNext(true) }) {
        Icon(Icons.Filled.ArrowForward, contentDescription = "Next match")
      }
      IconButton(
        onClick = {
          webView?.clearMatches()
          state.showFindInPage = false
          state.findQuery = ""
        },
      ) {
        Icon(Icons.Filled.Close, contentDescription = "Close find bar")
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabSwitcherSheet(
  state: BrowserState,
  onClose: () -> Unit,
  onCloseTab: (String) -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onClose) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("Tabs (${state.tabs.size})", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { state.newTab(); onClose() }) { Text("New tab") }
      }
      LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
        items(state.tabs, key = { it.id }) { tab ->
          TabRow(
            tab = tab,
            selected = tab.id == state.activeTabId,
            onSelect = { state.switchTab(tab.id); onClose() },
            onCloseTab = { onCloseTab(tab.id) },
          )
        }
      }
    }
  }
}

@Composable
private fun TabRow(tab: BrowserTab, selected: Boolean, onSelect: () -> Unit, onCloseTab: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onSelect)
      .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val favicon = tab.favicon
    if (favicon != null) {
      Image(bitmap = favicon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(20.dp))
    } else {
      Box(
        modifier = Modifier
          .size(20.dp)
          .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
      )
    }
    Spacer(modifier = Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = tab.title.ifBlank { displayLabelFor(tab.url) },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        text = if (tab.isIncognito) {
          "Incognito \u00b7 ${displayLabelFor(tab.url)}"
        } else {
          displayLabelFor(tab.url)
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    IconButton(onClick = onCloseTab) {
      Icon(Icons.Filled.Close, contentDescription = "Close tab")
    }
  }
}

@Composable
private fun SimpleListRow(
  title: String,
  subtitle: String,
  onClick: () -> Unit,
  trailing: @Composable (() -> Unit)? = null,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
      Text(
        subtitle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    trailing?.invoke()
  }
}

@Composable
private fun EmptyRow(text: String) {
  Text(text, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(
  entries: List<HistoryEntry>,
  onOpen: (String) -> Unit,
  onClear: () -> Unit,
  onClose: () -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onClose) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("History", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onClear, enabled = entries.isNotEmpty()) { Text("Clear") }
      }
      if (entries.isEmpty()) {
        EmptyRow("No history yet")
      } else {
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
          items(entries, key = { it.url + it.visitedAt }) { entry ->
            SimpleListRow(
              title = entry.title.ifBlank { displayLabelFor(entry.url) },
              subtitle = displayLabelFor(entry.url),
              onClick = { onOpen(entry.url) },
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarksSheet(
  entries: List<BookmarkEntry>,
  onOpen: (String) -> Unit,
  onRemove: (String) -> Unit,
  onClose: () -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onClose) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
      Text("Bookmarks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
      if (entries.isEmpty()) {
        EmptyRow("No bookmarks yet")
      } else {
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
          items(entries, key = { it.url }) { entry ->
            SimpleListRow(
              title = entry.title.ifBlank { displayLabelFor(entry.url) },
              subtitle = displayLabelFor(entry.url),
              onClick = { onOpen(entry.url) },
              trailing = {
                IconButton(onClick = { onRemove(entry.url) }) {
                  Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove bookmark")
                }
              },
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageDownloadsSheet(
  resources: List<PageResource>,
  onDownload: (PageResource) -> Unit,
  onClose: () -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onClose) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
      Text(
        "Downloads found on this page",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(16.dp),
      )
      if (resources.isEmpty()) {
        EmptyRow("No downloadable resources detected")
      } else {
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
          items(resources, key = { it.url }) { resource ->
            SimpleListRow(
              title = resource.label.ifBlank { displayLabelFor(resource.url) },
              subtitle = resource.url,
              onClick = { onDownload(resource) },
              trailing = {
                IconButton(onClick = { onDownload(resource) }) {
                  Icon(Icons.Outlined.CloudDownload, contentDescription = "Download")
                }
              },
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserMenuSheet(
  state: BrowserState,
  onDismiss: () -> Unit,
  onNewTab: () -> Unit,
  onNewIncognitoTab: () -> Unit,
  onToggleDesktopMode: () -> Unit,
  onToggleJavaScript: () -> Unit,
  onFindInPage: () -> Unit,
  onFindDownloads: () -> Unit,
  onOpenHistory: () -> Unit,
  onOpenBookmarks: () -> Unit,
  onShare: () -> Unit,
  onCloseBrowser: () -> Unit,
) {
  val tab = state.activeTab
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
      MenuRow(Icons.Filled.Add, "New tab") { onNewTab(); onDismiss() }
      MenuRow(null, "New incognito tab") { onNewIncognitoTab(); onDismiss() }
      HorizontalDivider()
      MenuRow(Icons.Filled.Search, "Find in page") { onFindInPage(); onDismiss() }
      MenuRow(Icons.Filled.Computer, "Desktop site", checked = tab?.desktopMode == true) {
        onToggleDesktopMode()
      }
      MenuRow(null, "JavaScript enabled", checked = state.javaScriptEnabled) {
        onToggleJavaScript()
      }
      MenuRow(Icons.Outlined.CloudDownload, "Find downloads on this page") {
        onFindDownloads()
        onDismiss()
      }
      HorizontalDivider()
      MenuRow(null, "History") { onOpenHistory(); onDismiss() }
      MenuRow(Icons.Filled.StarBorder, "Bookmarks") { onOpenBookmarks(); onDismiss() }
      MenuRow(null, "Share page") { onShare(); onDismiss() }
      HorizontalDivider()
      MenuRow(Icons.Filled.Close, "Close browser") { onCloseBrowser() }
    }
  }
}

@Composable
private fun MenuRow(icon: ImageVector?, label: String, checked: Boolean? = null, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
      if (icon != null) Icon(icon, contentDescription = null)
    }
    Spacer(modifier = Modifier.width(24.dp))
    Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    if (checked != null) {
      Switch(checked = checked, onCheckedChange = { onClick() })
    }
  }
}

@Composable
private fun SslWarningDialog(error: SslError, onProceed: () -> Unit, onCancel: () -> Unit) {
  AlertDialog(
    onDismissRequest = onCancel,
    icon = { Icon(Icons.Filled.WarningAmber, contentDescription = null) },
    title = { Text("Connection is not private") },
    text = {
      Text(
        "This site's security certificate is not trusted (error code ${error.primaryError}). " +
          "Continuing may expose your data to an attacker.",
      )
    },
    confirmButton = { TextButton(onClick = onCancel) { Text("Go back") } },
    dismissButton = { TextButton(onClick = onProceed) { Text("Proceed anyway") } },
  )
}

private fun launchExternalIntent(context: Context, url: String) {
  runCatching {
    val intent = if (url.startsWith("intent://", ignoreCase = true)) {
      Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
    } else {
      Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }
    intent.addCategory(Intent.CATEGORY_BROWSABLE)
    intent.component = null
    intent.selector = null
    context.startActivity(intent)
  }.onFailure { e -> log.w(e) { "No app can handle: $url" } }
}

private const val PAGE_SCAN_JS = """
(function() {
  var out = [];
  var seen = {};
  function add(url, label) {
    if (!url || seen[url] || out.length >= 50) return;
    seen[url] = true;
    out.push({url: url, label: (label || '').trim().slice(0, 60)});
  }
  var pattern = /\.(zip|rar|7z|tar|gz|pdf|docx?|xlsx?|pptx?|mp3|mp4|mkv|avi|mov|apk|iso|exe|torrent)(\?|${'$'})/i;
  var anchors = document.querySelectorAll('a[href]');
  for (var i = 0; i < anchors.length; i++) {
    var href = anchors[i].href;
    if (href && (pattern.test(href) || href.indexOf('magnet:') === 0)) {
      add(href, anchors[i].textContent);
    }
  }
  var media = document.querySelectorAll('video, audio, source');
  for (var j = 0; j < media.length; j++) {
    add(media[j].currentSrc || media[j].src, 'Media file');
  }
  return JSON.stringify(out);
})();
"""

@Serializable
private data class ScannedResource(val url: String, val label: String)

private fun scanPageForDownloads(
  webView: WebView?,
  results: SnapshotStateList<PageResource>,
  onScanned: () -> Unit,
) {
  if (webView == null) return
  webView.evaluateJavascript(PAGE_SCAN_JS) { raw ->
    if (raw.isNullOrBlank() || raw == "null") return@evaluateJavascript
    runCatching {
      val unwrapped: String = Json.decodeFromString(raw)
      val items: List<ScannedResource> = Json.decodeFromString(unwrapped)
      results.clear()
      items.filter { isKetchDownloadableScheme(it.url) }.forEach { item ->
        results.add(PageResource(item.url, item.label.ifBlank { displayLabelFor(item.url) }))
      }
    }.onFailure { e -> log.w(e) { "Failed to parse page scan result" } }
    onScanned()
  }
}

/** Persists the non-incognito tab URLs across process death / config changes. */
private val BrowserStateSaver: Saver<BrowserState, String> = Saver(
  save = { state -> Json.encodeToString(state.tabs.filterNot { it.isIncognito }.map { it.url }) },
  restore = { raw ->
    val urls = runCatching { Json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    BrowserState().apply {
      if (urls.isEmpty()) {
        newTab()
      } else {
        urls.forEach { newTab(it) }
        switchTab(tabs.first().id)
      }
    }
  },
)
