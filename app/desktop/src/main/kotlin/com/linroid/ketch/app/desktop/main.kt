package com.linroid.ketch.app.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.app.App
import com.linroid.ketch.config.FileConfigStore
import com.linroid.ketch.config.defaultConfigDir
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.ftp.FtpDownloadSource
import com.linroid.ketch.sqlite.DriverFactory
import com.linroid.ketch.sqlite.createSqliteTaskStore
import com.linroid.ketch.torrent.TorrentDownloadSource
import java.io.File
import java.net.InetAddress

fun main() = application {
  val ketch = remember {
    val configDir = defaultConfigDir()
    val configStore = FileConfigStore(
      configDir + File.separator + "config.toml",
    )
    val config = configStore.load()
    val dbPath = configDir + File.separator + "ketch.db"
    val taskStore = createSqliteTaskStore(DriverFactory(dbPath))
    val defaultDownloadsDir = System.getProperty("user.home") +
      File.separator + "Downloads"
    val downloadConfig = config.download.copy(
      defaultDirectory = config.download.defaultDirectory
        ?: defaultDownloadsDir,
    )
    val instanceName = config.name?.ifEmpty { null }
      ?: InetAddress.getLocalHost().hostName.removeSuffix(".local")
    Ketch(
      httpEngine = KtorHttpEngine(),
      taskStore = taskStore,
      config = downloadConfig,
      name = instanceName,
      logger = Logger.console(),
      additionalSources = listOf(
        FtpDownloadSource(),
        TorrentDownloadSource(),
      ),
    )
  }
  LaunchedEffect(ketch) { ketch.start() }
  DisposableEffect(ketch) {
    onDispose { ketch.close() }
  }
  Window(
    onCloseRequest = ::exitApplication,
    title = "Ketch",
    icon = painterResource("icon.svg"),
  ) {
    App(ketch)
  }
}
