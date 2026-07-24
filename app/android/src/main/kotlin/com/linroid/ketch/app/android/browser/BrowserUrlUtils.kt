package com.linroid.ketch.app.android.browser

import com.linroid.ketch.api.log.KetchLogger
import java.net.URI
import java.net.URLEncoder

/** Shared logger for the whole `browser` package (same-package, no import needed). */
internal val log = KetchLogger("Browser")

/**
 * Desktop Chrome user agent used when "Desktop site" mode is enabled
 * for a tab. Mirrors what Chrome for Android itself does: swap in a
 * fixed desktop-shaped UA string rather than deriving one, since
 * sites generally only check for the absence of "Mobile".
 */
internal const val DESKTOP_USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

private const val SEARCH_URL_PREFIX = "https://www.google.com/search?q="

private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
private val HOSTNAME_REGEX = Regex(
  "^[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?" +
    "(\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?)+(:[0-9]{1,5})?(/.*)?$",
)

/**
 * Resolves free-form address-bar input into a navigable URL.
 *
 * Absolute URLs (any `scheme://`) and `magnet:` links pass through
 * unchanged. Bare hostnames (e.g. `example.com`) are promoted to
 * `https://`. Anything else -- including multi-word text -- is
 * treated as a search query.
 */
internal fun resolveNavigationInput(input: String): String {
  val trimmed = input.trim()
  if (trimmed.isEmpty()) return ""
  if (trimmed.startsWith("magnet:", ignoreCase = true)) return trimmed
  if (SCHEME_REGEX.containsMatchIn(trimmed)) return trimmed
  if (!trimmed.contains(" ") && HOSTNAME_REGEX.matches(trimmed)) {
    return "https://$trimmed"
  }
  return SEARCH_URL_PREFIX + URLEncoder.encode(trimmed, "UTF-8")
}

/** Whether [url] is a scheme Ketch's downloader can plausibly handle. */
internal fun isKetchDownloadableScheme(url: String): Boolean {
  return url.startsWith("http://", ignoreCase = true) ||
    url.startsWith("https://", ignoreCase = true) ||
    url.startsWith("magnet:", ignoreCase = true) ||
    url.startsWith("ftp://", ignoreCase = true)
}

/** Best-effort human-readable label for a URL, used in list rows. */
internal fun displayLabelFor(url: String): String {
  return runCatching { URI(url).host }.getOrNull() ?: url
}
