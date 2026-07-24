package com.linroid.ketch.app.android.browser

import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

/** Start page opened for a brand-new tab. */
internal const val HOME_URL = "https://www.google.com"

/**
 * A single browser tab's mutable, Compose-observable navigation
 * state. Fields are updated in place from WebView callbacks so a
 * progress tick or title change recomposes only the small UI region
 * reading that field, instead of replacing a list item wholesale.
 *
 * Privacy note: [isIncognito] tabs are excluded from history and
 * bookmarking, and their WebView cache/history/form-data is cleared
 * on close. Cookies are a process-wide WebView resource on the
 * classic (non-multi-profile) API used here, so this means "not
 * remembered by Ketch," not full cross-tab cookie isolation.
 */
internal class BrowserTab(
  initialUrl: String,
  val isIncognito: Boolean = false,
  val id: String = UUID.randomUUID().toString(),
) {
  var url by mutableStateOf(initialUrl)
  var title by mutableStateOf("")
  var progress by mutableStateOf(0)
  var isLoading by mutableStateOf(false)
  var canGoBack by mutableStateOf(false)
  var canGoForward by mutableStateOf(false)
  var favicon by mutableStateOf<Bitmap?>(null)
  var loadError by mutableStateOf<String?>(null)
  var desktopMode by mutableStateOf(false)

  /** WebView navigation history, kept while this tab is backgrounded. */
  var savedState: Bundle? = null
}

/** Mutually exclusive full-screen/bottom-sheet panels. */
internal enum class BrowserOverlay { TabSwitcher, History, Bookmarks, PageDownloads }

/** A downloadable resource found by the on-demand page scan. */
internal data class PageResource(val url: String, val label: String)

/**
 * Navigation/UI state for the in-app browser. Deliberately holds no
 * [android.webkit.WebView] reference: the composable hosting the
 * active tab owns the live WebView and releases it the instant the
 * tab is backgrounded (see `WebViewHost` in BrowserScreen.kt), so at
 * most one WebView exists at a time no matter how many tabs are open.
 */
internal class BrowserState {
  val tabs = mutableStateListOf<BrowserTab>()

  var activeTabId by mutableStateOf<String?>(null)
    private set

  val activeTab: BrowserTab?
    get() = tabs.firstOrNull { it.id == activeTabId }

  var overlay by mutableStateOf<BrowserOverlay?>(null)
  var showMenu by mutableStateOf(false)
  var showFindInPage by mutableStateOf(false)
  var findQuery by mutableStateOf("")
  var findActiveMatch by mutableStateOf(0)
  var findTotalMatches by mutableStateOf(0)
  var javaScriptEnabled by mutableStateOf(true)
  var addressBarText by mutableStateOf("")
  var isAddressBarEditing by mutableStateOf(false)

  val history = mutableStateListOf<HistoryEntry>()
  val bookmarks = mutableStateListOf<BookmarkEntry>()
  val pageResources = mutableStateListOf<PageResource>()

  fun isBookmarked(url: String): Boolean = bookmarks.any { it.url == url }

  fun newTab(url: String = HOME_URL, isIncognito: Boolean = false): BrowserTab {
    val tab = BrowserTab(initialUrl = url, isIncognito = isIncognito)
    tabs.add(tab)
    activeTabId = tab.id
    addressBarText = url
    return tab
  }

  /** Removes [id], activating a neighboring tab or opening a fresh one. */
  fun closeTab(id: String) {
    val idx = tabs.indexOfFirst { it.id == id }
    if (idx < 0) return
    tabs.removeAt(idx)
    if (tabs.isEmpty()) {
      newTab()
      return
    }
    if (activeTabId == id) {
      switchTab(tabs[idx.coerceAtMost(tabs.lastIndex)].id)
    }
  }

  fun switchTab(id: String) {
    val tab = tabs.firstOrNull { it.id == id } ?: return
    activeTabId = id
    addressBarText = tab.url
  }
}
