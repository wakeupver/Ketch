package com.linroid.ketch.app.android.browser

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.linroid.ketch.app.android.KetchService
import com.linroid.ketch.app.theme.KetchTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Full-featured, WebView-backed in-app browser.
 *
 * Binds to the same [KetchService] the rest of the app uses so
 * downloads started here land in the exact same task list and
 * database -- never a second, disconnected download engine. Launch
 * with [EXTRA_URL] to open directly to a URL; otherwise opens to the
 * browser's default home page.
 */
internal class BrowserActivity : ComponentActivity() {

  private val activeWebViewHolder = MutableStateFlow<WebView?>(null)
  private var boundService: KetchService? by mutableStateOf(null)
  private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null
  private var backAction: (() -> Boolean)? = null

  private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
      boundService = (binder as KetchService.LocalBinder).service
    }

    override fun onServiceDisconnected(name: ComponentName) {
      boundService = null
    }
  }

  private val fileChooserLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val callback = pendingFileChooserCallback
      pendingFileChooserCallback = null
      val uris = result.data
        ?.takeIf { result.resultCode == RESULT_OK }
        ?.let { WebChromeClient.FileChooserParams.parseResult(result.resultCode, it) }
      callback?.onReceiveValue(uris ?: emptyArray())
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    CookieManager.getInstance().setAcceptCookie(true)
    bindService(Intent(this, KetchService::class.java), serviceConnection, BIND_AUTO_CREATE)

    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          if (backAction?.invoke() != true) {
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            isEnabled = true
          }
        }
      },
    )

    val initialUrl = intent?.getStringExtra(EXTRA_URL)
    setContent {
      KetchTheme {
        val service = boundService
        if (service == null) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
        } else {
          BrowserRoot(
            instanceManager = service.instanceManager,
            historyStore = remember { BrowserHistoryStore(applicationContext) },
            initialUrl = initialUrl,
            activeWebViewHolder = activeWebViewHolder,
            onShowFileChooser = ::launchFileChooser,
            registerBackAction = { action -> backAction = action },
            onFinish = { finish() },
          )
        }
      }
    }
  }

  /** Wired into [android.webkit.WebChromeClient.onShowFileChooser]. */
  private fun launchFileChooser(
    callback: ValueCallback<Array<Uri>>,
    params: WebChromeClient.FileChooserParams,
  ): Boolean {
    pendingFileChooserCallback?.onReceiveValue(null)
    pendingFileChooserCallback = callback
    return runCatching { fileChooserLauncher.launch(params.createIntent()) }
      .onFailure {
        pendingFileChooserCallback = null
        callback.onReceiveValue(null)
      }
      .isSuccess
  }

  override fun onDestroy() {
    runCatching { unbindService(serviceConnection) }
    super.onDestroy()
  }

  companion object {
    const val EXTRA_URL = "com.linroid.ketch.app.android.browser.EXTRA_URL"
  }
}
