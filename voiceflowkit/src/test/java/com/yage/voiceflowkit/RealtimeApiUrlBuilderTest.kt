package com.yage.voiceflowkit

import com.yage.voiceflowkit.internal.RealtimeApiUrlBuilder
import com.yage.voiceflowkit.internal.RealtimeTranscriptionError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [RealtimeApiUrlBuilder] normalizes base URLs, resolves REST paths against
 * a base that carries its own path segment (`/backend`), and converts a server-returned
 * `ws_url` into a `ws`/`wss` URL — including the relative-path prefixing behavior ported
 * from the Swift `RealtimeAPIURLBuilder`.
 */
class RealtimeApiUrlBuilderTest {

    @Test
    fun `normalizedBaseURL prefixes https when scheme missing`() {
        assertEquals(
            "https://space.ai-builders.com/backend",
            RealtimeApiUrlBuilder.normalizedBaseURL("space.ai-builders.com/backend"),
        )
    }

    @Test
    fun `normalizedBaseURL preserves explicit http scheme`() {
        assertEquals(
            "http://localhost:8080",
            RealtimeApiUrlBuilder.normalizedBaseURL("  http://localhost:8080  "),
        )
    }

    @Test
    fun `normalizedBaseURL throws on blank input`() {
        assertThrows(RealtimeTranscriptionError.InvalidBaseUrl::class.java) {
            RealtimeApiUrlBuilder.normalizedBaseURL("   ")
        }
    }

    @Test
    fun `buildAPIURL keeps the base path segment`() {
        // The base carries a `/backend` path; resolving must NOT drop it.
        val url = RealtimeApiUrlBuilder.buildAPIURL(
            "https://space.ai-builders.com/backend",
            "/v1/audio/realtime/sessions",
        )
        assertEquals(
            "https://space.ai-builders.com/backend/v1/audio/realtime/sessions",
            url,
        )
    }

    @Test
    fun `buildAPIURL works when base has no path`() {
        val url = RealtimeApiUrlBuilder.buildAPIURL(
            "https://example.com",
            "/v1/embeddings",
        )
        assertEquals("https://example.com/v1/embeddings", url)
    }

    @Test
    fun `realtimeWebSocketURL maps https to wss`() {
        val url = RealtimeApiUrlBuilder.realtimeWebSocketURL(
            "https://example.com",
            "https://example.com/realtime?ticket=abc",
        )
        assertEquals("wss://example.com/realtime?ticket=abc", url)
    }

    @Test
    fun `realtimeWebSocketURL maps http to ws`() {
        val url = RealtimeApiUrlBuilder.realtimeWebSocketURL(
            "http://localhost:8080",
            "http://localhost:8080/realtime?ticket=abc",
        )
        assertEquals("ws://localhost:8080/realtime?ticket=abc", url)
    }

    @Test
    fun `realtimeWebSocketURL resolves a relative ws_url and prefixes the base path`() {
        // Server returns a bare absolute path that does not include the base's `/backend`
        // segment. Swift behavior: prefix the base path so it reaches `/backend/realtime`.
        val url = RealtimeApiUrlBuilder.realtimeWebSocketURL(
            "https://space.ai-builders.com/backend",
            "/realtime?ticket=xyz",
        )
        assertEquals(
            "wss://space.ai-builders.com/backend/realtime?ticket=xyz",
            url,
        )
    }

    @Test
    fun `realtimeWebSocketURL does not double-prefix when ws_url already includes the base path`() {
        val url = RealtimeApiUrlBuilder.realtimeWebSocketURL(
            "https://space.ai-builders.com/backend",
            "/backend/realtime?ticket=xyz",
        )
        assertEquals(
            "wss://space.ai-builders.com/backend/realtime?ticket=xyz",
            url,
        )
    }

    @Test
    fun `realtimeWebSocketURL preserves the ticket query`() {
        val url = RealtimeApiUrlBuilder.realtimeWebSocketURL(
            "https://example.com",
            "/realtime?ticket=long-ticket-value&foo=bar",
        )
        assertTrue(url.startsWith("wss://example.com/realtime"))
        assertTrue(url.contains("ticket=long-ticket-value"))
        assertTrue(url.contains("foo=bar"))
    }
}
