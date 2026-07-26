package com.linroid.ketch.config

import com.linroid.ketch.api.DownloadConfig
import kotlinx.serialization.Serializable

/**
 * Shared configuration for Ketch apps and CLI.
 *
 * @property name user-visible name for this instance.
 *   When `null`, the app falls back to the platform default
 *   (e.g. device model on Android, hostname on desktop).
 * @property download download engine settings.
 */
@Serializable
data class KetchConfig(
  val name: String? = null,
  val download: DownloadConfig = DownloadConfig(),
)
