package com.lakesidegames.electioneer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lakesidegames.electioneer.BuildConfig
import java.util.concurrent.atomic.AtomicInteger

private enum class AskPhase { LOADING, RETRYING, READY, FAILED }

private class AskBrowser(private val context: Context, private val smoke: String?) {
    var phase by mutableStateOf(AskPhase.LOADING)
        private set
    var notice by mutableStateOf("")
        private set
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var retries = 0
    private var campaignUrl = ""
    private var activeUrl = ""
    private var mayRetry = true
    private val fixtureRequests = AtomicInteger()
    private val allowedHosts = setOf("ask.lakesidegames.net", "auth.lakesidegames.net",
        "auth.ahousedividedgame.com", "ahousedividedgame.com", "www.ahousedividedgame.com",
        "sandbox.ahousedividedgame.com", "accounts.lakesidegames.net", "discord.com",
        "accounts.google.com", "www.google.com")
    var view: WebView by mutableStateOf(createView())
        private set

    private fun createView(): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        setBackgroundColor(android.graphics.Color.BLACK)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.userInfo != null) { recover("This page cannot be opened in Ask."); return true }
                val allowed = url.scheme == "https" && (url.port == -1 || url.port == 443) && url.host in allowedHosts
                if (allowed) {
                    if (request.isForMainFrame) mayRetry = request.method == "GET" && url.host == "ask.lakesidegames.net"
                    return false
                }
                if (request.isForMainFrame) runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                return true
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (phase == AskPhase.RETRYING || phase == AskPhase.FAILED) return
                activeUrl = url
                generation++
                phase = AskPhase.LOADING
                armTimeout()
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (url == activeUrl && phase == AskPhase.LOADING) checkDocument(generation, 5)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (current(request) && !error.description.contains("ERR_ABORTED")) recover("Ask could not connect. Check your connection and try again.")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (current(request)) recover("Ask could not connect. Try again.")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                generation++
                handler.removeCallbacksAndMessages(null)
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
                this@AskBrowser.view = createView()
                phase = AskPhase.LOADING
                recover("Ask stopped responding. Try again.")
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!BuildConfig.DEBUG || smoke == null || request.url.host != "mov-ask.invalid" || !request.isForMainFrame) return null
                val count = fixtureRequests.incrementAndGet()
                val failed = smoke == "ask-retry" && count <= 2
                android.util.Log.i("MOVAsk", if (failed) "MOV_ASK_FIXTURE_RESPONSE_503" else "MOV_ASK_FIXTURE_RESPONSE_200")
                val html = if (smoke == "ask-blank") "<html><body></body></html>" else """
                    <html><body style="background:#10131c;color:white;font:24px sans-serif;padding:32px">
                    <h1>Ask recovered</h1><p>This document loaded after navigation recovery. Campaign questions are ready inside the app.</p>
                    </body></html>
                """.trimIndent()
                return WebResourceResponse("text/html", "utf-8", if (failed) 503 else 200,
                    if (failed) "Unavailable" else "OK", emptyMap(), html.byteInputStream())
            }
        }
    }

    private fun current(request: WebResourceRequest) = request.isForMainFrame && phase == AskPhase.LOADING &&
        request.url.toString().substringBefore('#') == activeUrl.substringBefore('#')

    fun open(url: String) {
        campaignUrl = url
        retries = 0
        load()
    }

    fun retry() { retries = 0; load() }

    private fun load() {
        generation++
        handler.removeCallbacksAndMessages(null)
        view.stopLoading()
        phase = AskPhase.LOADING
        mayRetry = true
        activeUrl = when {
            !BuildConfig.DEBUG || smoke == null -> campaignUrl
            smoke == "ask-offline" -> "https://127.0.0.1:1/"
            else -> "https://mov-ask.invalid/"
        }
        view.loadUrl(activeUrl)
        armTimeout()
    }

    private fun armTimeout() {
        handler.removeCallbacksAndMessages(null)
        val current = generation
        handler.postDelayed({ if (generation == current && phase == AskPhase.LOADING) recover("Ask did not finish loading. Try again.") }, 25_000)
    }

    private fun recover(message: String) {
        if (phase != AskPhase.LOADING) return
        handler.removeCallbacksAndMessages(null)
        generation++
        activeUrl = ""
        phase = AskPhase.RETRYING
        view.stopLoading()
        if (retries == 0 && mayRetry) {
            retries++
            val current = generation
            if (BuildConfig.DEBUG) android.util.Log.i("MOVAsk", "MOV_ASK_AUTOMATIC_RETRY")
            handler.postDelayed({ if (generation == current) load() }, 500)
        } else {
            phase = AskPhase.FAILED
            notice = message
            if (BuildConfig.DEBUG) android.util.Log.i("MOVAsk", "MOV_ASK_ERROR_SHOWN")
        }
    }

    private fun checkDocument(current: Int, attempts: Int) {
        if (generation != current || phase != AskPhase.LOADING) return
        view.evaluateJavascript("document.body?.innerText?.trim().length ?? 0") { result ->
            if (generation != current || phase != AskPhase.LOADING) return@evaluateJavascript
            if ((result.toIntOrNull() ?: 0) >= 60) {
                handler.removeCallbacksAndMessages(null)
                phase = AskPhase.READY
                retries = 0
                if (BuildConfig.DEBUG) {
                    android.util.Log.i("MOVAsk", if (Uri.parse(activeUrl).host == "auth.lakesidegames.net")
                        "MOV_ASK_SIGNIN_REACHED_EMBEDDED_AUTH" else "MOV_ASK_DOCUMENT_READY")
                    if (Uri.parse(activeUrl).host == "ask.lakesidegames.net") view.evaluateJavascript(
                        "(() => { try { const s = JSON.parse(sessionStorage.getItem('ask.movSnapshot')); return s?.version === 1 && s?.game === 'electioneer' && typeof s?.scenario === 'string' ? s.country : null; } catch { return null; } })()"
                    ) {
                        val country = it.trim('"')
                        if (country in listOf("US", "UK", "CA", "DE", "FR", "AU")) {
                            android.util.Log.i("MOVAsk", "MOV_ASK_SNAPSHOT_READY")
                            android.util.Log.i("MOVAsk", "MOV_ASK_SNAPSHOT_COUNTRY_$country")
                        }
                    }
                }
            } else if (attempts > 1) {
                handler.postDelayed({ checkDocument(current, attempts - 1) }, 1000)
            } else recover("Ask opened an empty page. Try again.")
        }
    }

    fun close() {
        generation++
        handler.removeCallbacksAndMessages(null)
        view.stopLoading()
        view.destroy()
    }
}

@Composable
fun AskDialog(url: String, onRefresh: () -> String, onClose: () -> Unit, smoke: String? = null) {
    val context = LocalContext.current
    val browser = remember { AskBrowser(context, if (BuildConfig.DEBUG) smoke else null) }
    DisposableEffect(browser) { onDispose { browser.close() } }
    LaunchedEffect(url) { browser.open(url) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text("Done") }
                    Text("Ask", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { browser.open(onRefresh()) }) { Text("Refresh campaign snapshot") }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    key(browser.view) {
                        AndroidView(factory = { browser.view }, modifier = Modifier.fillMaxSize(),
                            update = { it.visibility = if (browser.phase == AskPhase.READY) android.view.View.VISIBLE else android.view.View.INVISIBLE })
                    }
                    when (browser.phase) {
                        AskPhase.LOADING, AskPhase.RETRYING -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            CircularProgressIndicator()
                            Text("Opening Ask…")
                        }
                        AskPhase.FAILED -> Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            Text("Ask couldn’t open", style = MaterialTheme.typography.headlineSmall)
                            Text(browser.notice)
                            Button(onClick = browser::retry) { Text("Try again") }
                        }
                        AskPhase.READY -> Unit
                    }
                }
            }
        }
    }
}
