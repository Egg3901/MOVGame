package com.lakesidegames.electioneer.ui

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lakesidegames.electioneer.engine.NativeLakesideLogin
import java.util.UUID

@Composable
fun LakesideLogin(onCode: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val flow = remember { NativeLakesideLogin(UUID.randomUUID().toString().replace("-", "")) }
    var notice by remember { mutableStateOf<String?>(null) }
    val browser = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                private fun navigate(uri: Uri, mainFrame: Boolean): Boolean {
                    if (uri.userInfo != null) { notice = "This page cannot be opened during sign-in."; return true }
                    if (flow.isCallback(uri.scheme, uri.host, uri.port, uri.path)) {
                        val code = flow.takeCode(uri.scheme, uri.host, uri.port, uri.path,
                            uri.getQueryParameters("state"), uri.getQueryParameters("lakeside_code"), uri.fragment, mainFrame)
                        if (code != null) onCode(code)
                        else notice = "This sign-in return could not be verified. Close and try again."
                        return true
                    }
                    if (!flow.allows(uri.scheme, uri.host, uri.port)) {
                        notice = "This page cannot be opened during sign-in."
                        return true
                    }
                    return false
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = navigate(request.url, request.isForMainFrame)
            }
            loadUrl(flow.startUrl())
        }
    }
    DisposableEffect(browser) { onDispose { browser.stopLoading(); browser.destroy() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                TextButton(onClick = onClose) { Text("Close Lakeside sign-in") }
                notice?.let { Text(it, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                AndroidView(factory = { browser }, modifier = Modifier.weight(1f))
            }
        }
    }
}
