package com.yage.voiceflowkit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in live integration test for the prompt-following path — the Kotlin
 * port of iOS `LiveBackendPromptFollowingTests`.
 *
 * Sends the checked-in TTS WAV (`tts_all_caps_24k.wav`, 24 kHz / mono /
 * PCM16) through [VoiceFlowClient.transcribe] to the REAL AI Builder
 * backend and asserts that the configured prompt actually reaches the
 * model — i.e. session creation, the WebSocket wire format, the library
 * plumbing, and model behavior all line up end-to-end. No microphone is
 * involved, so this validates the real client ↔ backend contract without
 * any device audio source.
 *
 * Gated by `VOICEFLOW_LIVE_WS=1` plus a real token so ordinary
 * `testDebugUnitTest` runs stay green and don't burn API credits. Drive it
 * with `scripts/test_live_integration.sh`, which loads `AI_BUILDER_TOKEN`
 * / `AI_BUILDER_SPACE_ENDPOINT` from `.env`.
 */
class LiveBackendPromptFollowingTest {

    @Test
    fun promptInstructsModelToShoutInAllCaps() {
        val credentials = LiveBackendCredentials.resolve()
        // Test is opt-in. Without env opt-in or a real token, skip so the
        // default unit-test run stays green.
        assumeTrue(
            "Set VOICEFLOW_LIVE_WS=1 and AI_BUILDER_TOKEN to run the live test",
            credentials != null,
        )
        credentials!!

        val fixture = LiveBackendFixtures.allCapsTtsWav()

        val config = VoiceFlowConfig(
            endpoint = credentials.endpoint,
            tokenProvider = { credentials.token },
            prompt = "Transcribe every word in ALL CAPS. Example: THIS IS A TEST.",
            terms = emptyList(),
        )
        val client = VoiceFlowClient(config)

        val transcript = runBlocking {
            client.transcribe(fixture).text.trim()
        }
        // Surface the real backend output so a human running the live test
        // can eyeball that the round trip actually worked.
        System.err.println("[live] backend transcript: $transcript")

        assertTrue(
            "Live transcript came back empty — backend or wiring broken",
            transcript.isNotEmpty(),
        )

        // Model behavior isn't deterministic, so we don't require the whole
        // sentence to be uppercased. Counting whole-word uppercase tokens is
        // the same shape of assertion the iOS suite makes.
        val words = transcript
            .split(Regex("[^A-Za-z]+"))
            .filter { it.length >= 2 }
        val uppercaseWords = words.filter { word -> word.all { it.isUpperCase() } }
        assertTrue(
            "Expected prompt to push model toward ALL CAPS, got transcript: $transcript",
            uppercaseWords.size >= 2,
        )
    }
}

/**
 * Minimal credential resolver scoped to the kit tests. Mirrors the iOS
 * `LiveBackendCredentials`. Returns null unless `VOICEFLOW_LIVE_WS=1` and a
 * non-placeholder token are present in the environment.
 */
internal object LiveBackendCredentials {
    data class Resolved(val token: String, val endpoint: String)

    fun resolve(): Resolved? {
        val env = System.getenv()
        if (env["VOICEFLOW_LIVE_WS"] != "1") return null

        val token = firstNonPlaceholder(
            env["AI_BUILDER_TOKEN"],
            env["VOICEFLOW_AI_BUILDER_TOKEN"],
        ) ?: return null

        val endpoint = env["AI_BUILDER_SPACE_ENDPOINT"]?.trim().orEmpty()
            .ifEmpty { VoiceFlowConfig.DEFAULT_ENDPOINT }
        return Resolved(token = token, endpoint = endpoint)
    }

    private fun firstNonPlaceholder(vararg candidates: String?): String? =
        candidates
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotEmpty() && it != "replace-with-your-real-token" }
}

internal object LiveBackendFixtures {
    /**
     * Resolve the checked-in TTS WAV from the unit-test resources. The
     * fixture is copied from the iOS test bundle
     * (`tts_all_caps_24k.wav`) and is already 24 kHz / mono / PCM16, which
     * is exactly what `transcribe(wavFile:)` expects.
     */
    fun allCapsTtsWav(): File {
        val resource = javaClass.classLoader
            ?.getResource("fixtures/tts_all_caps_24k.wav")
            ?: error(
                "Live fixture 'fixtures/tts_all_caps_24k.wav' is missing from the test " +
                    "resources. Copy it from the iOS repo's " +
                    "Tests/VoiceFlowKitTests/Fixtures/.",
            )
        return File(resource.toURI())
    }
}
