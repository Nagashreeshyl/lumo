package com.nagashreeshyl.lumo.auth

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.music.innertube.YouTube
import com.nagashreeshyl.lumo.ui.app
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Optional Google sign-in. The password is typed into Google's own page; only the YouTube session cookies are kept,
 * in the app's private storage.
 */
class Session(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    val account = MutableStateFlow(prefs.getString("name", null))

    init { prefs.getString("cookie", null)?.takeIf { hasSession(it) }?.let { apply(it) } }

    private fun apply(cookie: String?) {
        YouTube.cookie = cookie
        YouTube.useLoginForBrowse = cookie != null
    }

    suspend fun signIn(cookie: String): Boolean {
        if (!hasSession(cookie)) return false
        apply(cookie)
        val name = YouTube.accountInfo().getOrNull()?.name ?: "your Google account"
        prefs.edit().putString("cookie", cookie).putString("name", name).apply()
        account.value = name
        return true
    }

    fun signOut() {
        prefs.edit().clear().apply()
        apply(null)
        account.value = null
        CookieManager.getInstance().removeAllCookies(null)
    }

    companion object {
        fun hasSession(cookie: String?): Boolean =
            cookie?.split(";")?.any { it.trim().substringBefore("=") == "SAPISID" && it.substringAfter("=", "").isNotBlank() } == true

        /** Google only offers the full sign-in flow to desktop browsers. */
        const val DESKTOP_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(nav: NavController) {
    val app = app()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        TopAppBar({ Text("Sign in") }, windowInsets = WindowInsets(0),
            navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
        AndroidView({ ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = Session.DESKTOP_UA
                CookieManager.getInstance().setAcceptCookie(true)
                webViewClient = object : WebViewClient() {
                    var done = false
                    override fun onPageFinished(view: WebView, url: String) {
                        if (done || !url.startsWith("https://music.youtube.com")) return
                        val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com")
                        if (Session.hasSession(cookie)) {
                            done = true
                            scope.launch { if (app.session.signIn(cookie)) nav.popBackStack() }
                        }
                    }
                }
                loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https://music.youtube.com")
            }
        }, Modifier.fillMaxSize())
    }
}
