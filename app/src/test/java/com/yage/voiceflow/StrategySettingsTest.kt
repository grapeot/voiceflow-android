package com.yage.voiceflow

import android.content.SharedPreferences
import com.yage.voiceflow.data.SettingsStore
import com.yage.voiceflowkit.VoiceFlowRecordingStrategy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StrategySettingsTest {
    @Test
    fun `settings round trips all strategies without changing persisted names`() {
        val values = mutableMapOf<String, String?>()
        val store = settingsStore(values)

        VoiceFlowRecordingStrategy.entries.forEach { strategy ->
            store.recordingStrategy = strategy
            assertEquals(strategy.name, values["recording_strategy"])
            assertEquals(strategy, store.recordingStrategy)
        }
    }

    @Test
    fun `settings defaults to GPT Live while unknown values remain GPT Realtime`() {
        assertEquals(
            VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE,
            settingsStore(mutableMapOf()).recordingStrategy,
        )
        assertEquals(
            VoiceFlowRecordingStrategy.OPENAI_REALTIME,
            settingsStore(mutableMapOf("recording_strategy" to "future-strategy")).recordingStrategy,
        )
    }

    @Test
    fun `prompt visibility follows realtime capability`() {
        assertTrue(VoiceFlowRecordingStrategy.OPENAI_REALTIME.usesRealtimeTransport)
        assertTrue(VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE.usesRealtimeTransport)
        assertFalse(VoiceFlowRecordingStrategy.GROK_BATCH.usesRealtimeTransport)
    }

    @Test
    fun `ordered audio sender drains every chunk before returning`() = runTest {
        val sent = mutableListOf<Int>()
        val sender = OrderedAudioSender(
            scope = this,
            send = { chunk ->
                delay(5)
                sent += chunk.first().toInt()
            },
        )

        assertTrue(sender.tryEnqueue(byteArrayOf(1)))
        assertTrue(sender.tryEnqueue(byteArrayOf(2)))
        assertTrue(sender.tryEnqueue(byteArrayOf(3)))
        assertTrue(sender.drain())

        assertEquals(listOf(1, 2, 3), sent)
    }

    @Test
    fun `ordered audio sender rejects overflow without blocking microphone`() = runTest {
        var releaseConsumer = false
        val sender = OrderedAudioSender(this, capacity = 1, send = {
            while (!releaseConsumer) delay(1)
        })

        assertTrue(sender.tryEnqueue(byteArrayOf(1)))
        runCurrent()
        assertTrue(sender.tryEnqueue(byteArrayOf(2)))
        assertFalse(sender.tryEnqueue(byteArrayOf(3)))

        releaseConsumer = true
        assertTrue(sender.drain())
    }

    @Test
    fun `ordered audio sender drain times out and cancels stalled network send`() = runTest {
        val sender = OrderedAudioSender(this, capacity = 1, send = {
            kotlinx.coroutines.awaitCancellation()
        })

        assertTrue(sender.tryEnqueue(byteArrayOf(1)))
        runCurrent()
        assertFalse(sender.drain(timeoutMs = 10))
    }

    @Test
    fun `attempt gate rejects a superseded transcription result`() {
        val gate = TranscriptionAttemptGate()
        val first = gate.next()
        val second = gate.next()

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
        gate.invalidate()
        assertFalse(gate.isCurrent(second))
    }

    private fun settingsStore(values: MutableMap<String, String?>): SettingsStore {
        val secure = mockk<SharedPreferences>(relaxed = true)
        val plain = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { plain.getString(any(), any()) } answers {
            values[firstArg()] ?: secondArg()
        }
        every { plain.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            values[firstArg()] = secondArg()
            editor
        }
        every { editor.apply() } returns Unit
        return SettingsStore(secure = secure, plain = plain)
    }
}
