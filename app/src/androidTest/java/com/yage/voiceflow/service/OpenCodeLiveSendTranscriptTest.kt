package com.yage.voiceflow.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Opt-in LIVE end-to-end test against a real OpenCode server.
 *
 * "Passing" means: a transcript submitted through the public client's real
 * [OpenCodeClient.sendTranscript] path is actually persisted in the session —
 * i.e. GET `{base}/session/{id}/message` contains a `[user]` message with the
 * sent text. A 204 from prompt_async alone is NOT sufficient (that is exactly
 * the silent-failure mode the broken agent caused), which is why the public
 * client now performs read-back verification inside `sendTranscript`.
 *
 * Credentials come from a root `.env` (gitignored, never committed), injected at
 * build time into instrumentation runner args by `app/build.gradle.kts`:
 *   OPENCODE_BASE_URL / OPENCODE_USERNAME / OPENCODE_PASSWORD
 * When OPENCODE_BASE_URL is absent the test self-skips via [assumeTrue], so the
 * default `connectedAndroidTest` run stays green and never touches the network.
 *
 * Emulator networking: the emulator cannot reach the host's `localhost` directly;
 * the host loopback is exposed at `10.0.2.2`. The test rewrites
 * `localhost` / `127.0.0.1` in the base URL to `10.0.2.2` so it can hit a server
 * running on the developer's machine.
 *
 * Run with:
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.yage.voiceflow.service.OpenCodeLiveSendTranscriptTest
 * (Requires a connected device/emulator and a reachable OpenCode server.)
 */
@RunWith(AndroidJUnit4::class)
class OpenCodeLiveSendTranscriptTest {

    private fun arg(name: String): String =
        InstrumentationRegistry.getArguments().getString(name).orEmpty().trim()

    /** Rewrite host loopback to the emulator's host-loopback alias (10.0.2.2). */
    private fun emulatorReachableUrl(raw: String): String =
        raw.replace("//localhost", "//10.0.2.2")
            .replace("//127.0.0.1", "//10.0.2.2")

    @Test
    fun sendTranscript_persistsUserMessageInSession() {
        val baseUrl = arg("OPENCODE_BASE_URL")
        val username = arg("OPENCODE_USERNAME")
        val password = arg("OPENCODE_PASSWORD")

        assumeTrue(
            "Skipping live e2e: OPENCODE_BASE_URL not set (copy .env.example to .env).",
            baseUrl.isNotBlank(),
        )
        assumeFalse(
            "Skipping live e2e: OPENCODE_PASSWORD looks like a placeholder.",
            password.isBlank() || password.startsWith("replace-with"),
        )

        val serverUrl = emulatorReachableUrl(baseUrl)
        val sentText = "VoiceFlow live e2e ${UUID.randomUUID()}"

        runBlocking {
            // The public client's sendTranscript creates a session, POSTs
            // prompt_async, then polls GET /session/{id}/message until a [user]
            // message with sentText appears. If the message never lands it throws
            // OpenCodeClientError.PromptSendFailed, so a clean return already
            // proves the read-back assertion. We additionally re-read the most
            // recent session below as an explicit, independent check.
            OpenCodeClient().sendTranscript(
                text = sentText,
                serverURL = serverUrl,
                username = username,
                password = password,
            )

            // Independent read-back: scan recent sessions for the [user] message.
            assertTrue(
                "Sent transcript was not found as a [user] message in any session.",
                anySessionContainsUserMessage(serverUrl, username, password, sentText),
            )
        }
    }

    private fun base64Auth(username: String, password: String): String {
        val raw = "$username:$password"
        val encoded = android.util.Base64.encodeToString(
            raw.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        return "Basic $encoded"
    }

    private fun anySessionContainsUserMessage(
        base: String,
        username: String,
        password: String,
        text: String,
    ): Boolean {
        val client = OkHttpClient()
        val auth = base64Auth(username, password)
        val normalizedBase = base.trimEnd('/')

        val sessionsReq = Request.Builder()
            .url("$normalizedBase/session")
            .get()
            .header("Authorization", auth)
            .build()
        val sessionsRaw = client.newCall(sessionsReq).execute().use { resp ->
            if (resp.code != 200) return false
            resp.body?.string().orEmpty()
        }
        val sessions = try { JSONArray(sessionsRaw) } catch (_: Throwable) { return false }

        for (i in 0 until sessions.length()) {
            val sessionId = sessions.optJSONObject(i)?.optString("id").orEmpty()
            if (sessionId.isEmpty()) continue

            val msgReq = Request.Builder()
                .url("$normalizedBase/session/$sessionId/message")
                .get()
                .header("Authorization", auth)
                .build()
            val msgRaw = client.newCall(msgReq).execute().use { resp ->
                if (resp.code != 200) return@use ""
                resp.body?.string().orEmpty()
            }
            val messages = try { JSONArray(msgRaw) } catch (_: Throwable) { continue }
            for (j in 0 until messages.length()) {
                val item = messages.optJSONObject(j) ?: continue
                if (item.optJSONObject("info")?.optString("role") != "user") continue
                val parts = item.optJSONArray("parts") ?: continue
                val joined = StringBuilder()
                for (k in 0 until parts.length()) {
                    val part = parts.optJSONObject(k) ?: continue
                    when {
                        part.has("text") -> joined.append(part.optString("text"))
                        part.has("content") -> joined.append(part.optString("content"))
                    }
                }
                if (joined.toString() == text) return true
            }
        }
        return false
    }
}
