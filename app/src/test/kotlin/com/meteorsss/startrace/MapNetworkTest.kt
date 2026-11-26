package com.meteorsss.startrace

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Local loopback server only; does not download public map tiles. */
class MapNetworkTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun identifiesAppWithoutNoCacheHeadersAndPreservesServerMetadata() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("tile").setHeader("Cache-Control", "max-age=604800")
                .setHeader("ETag", "tile-v1"))
            val client = MapNetwork.createClient(folder.newFolder()) { true }
            try {
                client.newCall(Request.Builder().url(server.url("/1/0/0.png")).build()).execute().use {
                    assertEquals("tile", it.body!!.string())
                    assertEquals("max-age=604800", it.header("Cache-Control"))
                    assertEquals("tile-v1", it.header("ETag"))
                }
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals(MapNetwork.USER_AGENT, request.getHeader("User-Agent"))
                assertNull(request.getHeader("Cache-Control")); assertNull(request.getHeader("Pragma"))
            } finally { client.cache!!.close(); client.dispatcher.executorService.shutdown() }
        }
    }
    @Test fun freshTilesUseDiskCacheWithoutSecondNetworkRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("tile").setHeader("Cache-Control", "max-age=604800"))
            val client = MapNetwork.createClient(folder.newFolder()) { true }
            try {
                val request = Request.Builder().url(server.url("/tile.png")).build()
                repeat(2) { client.newCall(request).execute().use { assertEquals("tile", it.body!!.string()) } }
                assertEquals(1, server.requestCount)
            } finally { client.cache!!.close(); client.dispatcher.executorService.shutdown() }
        }
    }
    @Test fun expiredTileUsesConditionalEtagValidation() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("tile").setHeader("Cache-Control", "max-age=0").setHeader("ETag", "tile-v1"))
            server.enqueue(MockResponse().setResponseCode(304).setHeader("Cache-Control", "max-age=604800"))
            val client = MapNetwork.createClient(folder.newFolder()) { true }
            try {
                val request = Request.Builder().url(server.url("/tile.png")).build()
                repeat(2) { client.newCall(request).execute().use { assertEquals("tile", it.body!!.string()) } }
                server.takeRequest(2, TimeUnit.SECONDS)
                assertEquals("tile-v1", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("If-None-Match"))
            } finally { client.cache!!.close(); client.dispatcher.executorService.shutdown() }
        }
    }
    @Test fun revokedConsentStopsRequestsBeforeNetwork() {
        MockWebServer().use { server ->
            val client = MapNetwork.createClient(folder.newFolder()) { false }
            try {
                try {
                    client.newCall(Request.Builder().url(server.url("/tile.png")).build()).execute().close()
                    fail("Consent must be checked before the request")
                } catch (_: IOException) { assertEquals(0, server.requestCount) }
            } finally { client.cache!!.close(); client.dispatcher.executorService.shutdown() }
        }
    }
}
