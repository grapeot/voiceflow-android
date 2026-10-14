package com.yage.voiceflow

import android.content.SharedPreferences
import com.yage.voiceflow.data.SettingsStore
import com.yage.voiceflow.model.AppLanguage
import com.yage.voiceflow.model.CustomActionConfig
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class CustomActionSettingsTest {
    @Test fun `localized defaults persist user choices without reading secrets`() {
        val values = mutableMapOf<String, String?>("app_language" to "english")
        val secure = mockk<SharedPreferences>()
        val plain = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { plain.getString(any(), any()) } answers { values[firstArg()] ?: secondArg() }
        every { plain.edit() } returns editor
        every { editor.putString(any(), any()) } answers { values[firstArg()] = secondArg(); editor }
        every { editor.apply() } just Runs
        val store = SettingsStore(secure, plain)
        assertEquals("Polish", store.customActionConfig.name)
        assertEquals("grok-4-fast", store.customActionConfig.model)
        store.language = AppLanguage.SimplifiedChinese
        assertEquals("润色", store.customActionConfig.name)
        val config = CustomActionConfig("My action", "Custom instructions", "deepseek-v4-flash")
        store.customActionConfig = config
        store.language = AppLanguage.English
        assertEquals(config, store.customActionConfig)
        verify { secure wasNot Called }
    }

    @Test fun `configuration requires name instructions and a supported model`() {
        assertTrue(CustomActionConfig.default(false).isConfigured)
        assertFalse(CustomActionConfig(" ", "text").isConfigured)
        assertFalse(CustomActionConfig("name", " ").isConfigured)
        assertFalse(CustomActionConfig("name", "text", "unknown").isConfigured)
        assertEquals(setOf("grok-4-fast", "deepseek-v4-flash"), CustomActionConfig.models.keys)
    }
}
