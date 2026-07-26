package com.linroid.ketch.app

import androidx.compose.runtime.Composable
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.AppShell

@Composable
fun App(
  api: KetchApi,
  onOpenBrowser: ((String?) -> Unit)? = null,
) {
  KetchTheme {
    AppShell(api, onOpenBrowser)
  }
}
