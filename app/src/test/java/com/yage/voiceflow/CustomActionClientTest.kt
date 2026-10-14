package com.yage.voiceflow

import com.yage.voiceflow.model.CustomActionFailure
import com.yage.voiceflow.service.CustomActionClient
import com.yage.voiceflow.service.CustomActionError
import io.mockk.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CustomActionClientTest {
    @Test fun `request uses the shared endpoint and strict text transformation contract`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"index":0,"finish_reason":"stop","message":{"content":" revised "}}]}"""))
            assertEquals("revised", CustomActionClient().transform("原文", "Polish", "grok-4-fast", server.url("/").toString(), "synthetic"))
            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer synthetic", request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals(setOf("model", "messages", "stream"), body.keys().asSequence().toSet())
            assertFalse(body.getBoolean("stream"))
            assertEquals("grok-4-fast", body.getString("model"))
            assertEquals("<<<TRANSCRIPT>>>\n原文\n<<<END_TRANSCRIPT>>>", body.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content").contains("Output ONLY the transformed text"))
        }
    }

    @Test fun `truncated tool only empty and malformed responses never become a result`() {
        val inputs = mapOf(
            """{"choices":[{"finish_reason":"length","message":{"content":"PRIVATE_RESPONSE_SENTINEL"}}]}""" to CustomActionFailure.Truncated,
            """{"choices":[{"finish_reason":"tool_calls","message":{"content":null}}]}""" to CustomActionFailure.Truncated,
            """{"choices":[{"finish_reason":"stop","message":{"content":"text","tool_calls":[{}]}}]}""" to CustomActionFailure.EmptyResult,
            """{"choices":[{"finish_reason":"stop","message":{"content":"  "}}]}""" to CustomActionFailure.EmptyResult,
            """{"choices":[]} """ to CustomActionFailure.EmptyResult,
            "PRIVATE_RESPONSE_SENTINEL" to CustomActionFailure.InvalidResponse,
        )
        inputs.forEach { (body, failure) ->
            val error = assertThrows(CustomActionError::class.java) { CustomActionClient.parse(body) }
            assertEquals(failure, error.reason)
            assertFalse(error.toString().contains("PRIVATE_RESPONSE_SENTINEL"))
            assertNull(error.cause)
        }
    }

    @Test fun `text parts and index zero are supported`() {
        assertEquals("AB", CustomActionClient.parse("""{"choices":[{"index":1},{"index":0,"finish_reason":"stop","message":{"content":[{"type":"text","text":"A"},{"type":"text","text":"B"}]}}]}"""))
    }

    @Test fun `text arrays reject every malformed item without coercion or partial output`() {
        val arrays = listOf(
            """[{"type":"text","text":"first"},42,{"type":"text","text":"third"}]""",
            """[{"type":"text","text":{"unexpected":"object"}}]""",
            """[{"type":"text","text":42}]""",
            """[{"type":"text","text":null}]""",
            """[{"type":"text","text":["array"]}]""",
            """[{"type":"image","text":"not a text part"}]""",
            """[{"text":"missing type"}]""",
            """[null]""",
        )
        for (parts in arrays) {
            val response = """{"choices":[{"finish_reason":"stop","message":{"content":$parts}}]}"""
            val error = assertThrows(CustomActionError::class.java) { CustomActionClient.parse(response) }
            assertEquals(CustomActionFailure.InvalidResponse, error.reason)
            assertEquals(CustomActionFailure.InvalidResponse.key, error.message)
            assertNull(error.cause)
        }
    }

    @Test fun `503 Retry-After zero is surfaced after exactly one POST`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"must not be used"}}]}"""))
            val result = runCatching {
                CustomActionClient().transform("source", "Polish", "grok-4-fast", server.url("/").toString(), "synthetic")
            }
            assertEquals("One action must produce only one server request", 1, server.requestCount)
            val error = result.exceptionOrNull() as? CustomActionError
            assertEquals(CustomActionFailure.Server, error?.reason)
            assertEquals("POST", server.takeRequest().method)
        }
    }

    @Test fun `HTTP failures expose only fixed categories not response or token`() = runTest {
        MockWebServer().use { server ->
            val failures = mapOf(401 to CustomActionFailure.Unauthorized, 403 to CustomActionFailure.Forbidden,
                429 to CustomActionFailure.RateLimited, 500 to CustomActionFailure.Server, 400 to CustomActionFailure.Request)
            for ((code, expected) in failures) {
                server.enqueue(MockResponse().setResponseCode(code).setBody("PRIVATE_RESPONSE_SENTINEL"))
                val error = try {
                    CustomActionClient().transform("text", "Polish", "deepseek-v4-flash", server.url("/").toString(), "SYNTHETIC_TOKEN_SENTINEL")
                    fail("Expected HTTP failure")
                    null
                } catch (error: CustomActionError) { error }
                assertEquals(expected, error!!.reason)
                assertFalse(error.toString().contains("PRIVATE_RESPONSE_SENTINEL"))
                assertFalse(error.toString().contains("SYNTHETIC_TOKEN_SENTINEL"))
                assertNull(error.cause)
            }
        }
    }

    @Test fun `coroutine cancellation cancels the OkHttp call`() = runTest {
        val call = mockk<Call>(relaxed = true)
        val http = mockk<OkHttpClient>()
        every { http.newCall(any()) } returns call
        val callback = slot<Callback>()
        every { call.enqueue(capture(callback)) } just Runs
        val job = launch { CustomActionClient(http).transform("text", "Polish", "grok-4-fast", "https://example.invalid", "synthetic") }
        runCurrent()
        job.cancel(); runCurrent()
        verify(exactly = 1) { call.cancel() }
        callback.captured.onFailure(call, IOException("PRIVATE_RESPONSE_SENTINEL"))
        assertTrue(job.isCancelled)
    }
}
