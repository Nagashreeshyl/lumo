package com.nagashreeshyl.lumo

import com.nagashreeshyl.lumo.player.StreamResolver
import com.nagashreeshyl.lumo.player.YTStreams
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class StreamResolverTest {
    @Test fun cachesUntilExpiryAndDropsOn403() = runBlocking {
        var calls = 0
        var now = 0L
        val r = StreamResolver(source = { id -> calls++; "https://x/$id?expire=${(now / 1000) + 3600}&n=$calls" }, now = { now })
        val first = r.resolve("a")
        assertEquals(first, r.resolve("a")); assertEquals(1, calls)
        r.onHttpError("a", 403)
        r.resolve("a"); assertEquals(2, calls)
        now += 3_600_000   // past expiry
        r.resolve("a"); assertEquals(3, calls)
    }

    @Test fun liveResolvesAPlayableUrl() = runBlocking {
        assumeTrue(System.getenv("LUMO_LIVE") == "1")
        // The app sets a visitor id for Home; streams must still be fully readable with it set.
        com.music.innertube.YouTube.visitorData = com.music.innertube.YouTube.visitorData().getOrNull()
        val url = YTStreams.find("fJ9rUzIMcZQ")   // Bohemian Rhapsody
        assertNotNull(url)
        val res = OkHttpClient().newCall(Request.Builder().url(url!!).header("Range", "bytes=0-1048575").build()).execute()
        assertTrue("HTTP ${res.code}", res.code == 206 || res.code == 200)
    }
}
