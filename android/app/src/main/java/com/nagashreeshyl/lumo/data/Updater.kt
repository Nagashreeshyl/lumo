package com.nagashreeshyl.lumo.data

import android.content.Context
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.nagashreeshyl.lumo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** Checks GitHub for a newer release and installs its APK over this one (same signing key, so data is kept). */
object Updater {
    @Serializable data class Asset(val name: String, val browser_download_url: String)
    @Serializable data class Release(val tag_name: String, val body: String = "", val assets: List<Asset> = emptyList()) {
        val version get() = tag_name.removePrefix("v")
        val apk get() = assets.firstOrNull { it.name.endsWith(".apk") }
    }

    private const val LATEST = "https://api.github.com/repos/Nagashreeshyl/lumo/releases/latest"
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    /** "0.10.0" > "0.9.1"; a dev build ("0.0.0-dev") is older than any release. */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val a = parts(latest); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** The latest release if it is newer than this build and has an APK; null otherwise or offline. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()).execute().use {
                if (!it.isSuccessful) return@use null
                json.decodeFromString<Release>(it.body.string())
            }
        }.getOrNull()?.takeIf { it.apk != null && isNewer(it.version, BuildConfig.VERSION_NAME) }
    }

    /** Downloads the APK and hands it to Android's installer, which asks the user to confirm. */
    suspend fun install(context: Context, release: Release) {
        val file = File(context.cacheDir, "updates/Lumo-${release.version}.apk").apply { parentFile?.mkdirs() }
        withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(release.apk!!.browser_download_url).build()).execute().use {
                check(it.isSuccessful) { "HTTP ${it.code}" }
                file.outputStream().use { out -> it.body.byteStream().copyTo(out) }
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Shown once per launch when a newer Lumo is on GitHub. */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<Updater.Release?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { release = Updater.check() }
    val r = release ?: return
    AlertDialog(
        onDismissRequest = { if (!busy) release = null },
        title = { Text("Lumo ${r.version} is available") },
        text = { Text(error ?: if (busy) "Downloading the update…" else r.body.take(600).ifBlank { "A new version of Lumo is ready." }) },
        confirmButton = {
            TextButton({
                busy = true; error = null
                scope.launch {
                    runCatching { Updater.install(context, r) }
                        .onSuccess { release = null }
                        .onFailure { error = "Couldn't download the update: ${it.message}" }
                    busy = false
                }
            }, enabled = !busy) { Text(if (error != null) "Try Again" else "Update") }
        },
        dismissButton = { TextButton({ release = null }, enabled = !busy) { Text("Later") } },
    )
}
