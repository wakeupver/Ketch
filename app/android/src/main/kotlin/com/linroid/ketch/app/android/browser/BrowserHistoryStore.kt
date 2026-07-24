package com.linroid.ketch.app.android.browser

import android.content.Context
import com.linroid.ketch.api.log.KetchLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A single visited page, most-recent first in [BrowserHistoryStore]. */
@Serializable
internal data class HistoryEntry(
  val url: String,
  val title: String,
  val visitedAt: Long,
)

/** A saved page shortcut. */
@Serializable
internal data class BookmarkEntry(
  val url: String,
  val title: String,
)

@Serializable
private data class BrowserData(
  val history: List<HistoryEntry> = emptyList(),
  val bookmarks: List<BookmarkEntry> = emptyList(),
)

/**
 * Persists browser history and bookmarks as a single JSON file under
 * the app's private files directory.
 *
 * Every public function acquires [mutex] before touching [cache] or
 * disk, so concurrent callers (e.g. a visit recorded while the
 * bookmark list loads) never interleave. All disk I/O runs on
 * [Dispatchers.IO]; callers must invoke these suspend functions from
 * a coroutine, never the main thread.
 *
 * @param context any context; only [Context.getApplicationContext]
 *   is retained, so this store never leaks an Activity.
 */
internal class BrowserHistoryStore(context: Context) {
  private val log = KetchLogger("BrowserHistoryStore")
  private val file = File(context.applicationContext.filesDir, FILE_NAME)
  private val mutex = Mutex()
  private val json = Json { ignoreUnknownKeys = true }

  private var cache: BrowserData? = null

  // Lock-free helpers: callers below always hold `mutex` already.
  // Mutex is not reentrant, so these must never acquire it again.
  private suspend fun read(): BrowserData {
    cache?.let { return it }
    val loaded = withContext(Dispatchers.IO) {
      runCatching {
        if (file.exists()) {
          json.decodeFromString(BrowserData.serializer(), file.readText())
        } else {
          BrowserData()
        }
      }.onFailure { e ->
        log.w(e) { "Failed to read browser data, starting fresh" }
      }.getOrDefault(BrowserData())
    }
    cache = loaded
    return loaded
  }

  private suspend fun write(data: BrowserData) {
    withContext(Dispatchers.IO) {
      runCatching {
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(BrowserData.serializer(), data))
        check(tmp.renameTo(file)) { "Rename to $file failed" }
      }.onFailure { e ->
        log.e(e) { "Failed to persist browser data" }
      }
    }
    cache = data
  }

  suspend fun loadHistory(): List<HistoryEntry> = mutex.withLock { read().history }

  suspend fun loadBookmarks(): List<BookmarkEntry> = mutex.withLock { read().bookmarks }

  suspend fun addVisit(entry: HistoryEntry): Unit = mutex.withLock {
    val current = read()
    val updated = (listOf(entry) + current.history).take(MAX_HISTORY_ENTRIES)
    write(current.copy(history = updated))
  }

  suspend fun clearHistory(): Unit = mutex.withLock {
    write(read().copy(history = emptyList()))
  }

  suspend fun addBookmark(entry: BookmarkEntry): Unit = mutex.withLock {
    val current = read()
    if (current.bookmarks.any { it.url == entry.url }) return@withLock
    write(current.copy(bookmarks = listOf(entry) + current.bookmarks))
  }

  suspend fun removeBookmark(url: String): Unit = mutex.withLock {
    val current = read()
    write(current.copy(bookmarks = current.bookmarks.filterNot { it.url == url }))
  }

  companion object {
    private const val FILE_NAME = "browser_data.json"
    const val MAX_HISTORY_ENTRIES = 500
  }
}
