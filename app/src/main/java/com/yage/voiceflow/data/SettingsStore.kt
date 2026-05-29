package com.yage.voiceflow.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.yage.voiceflow.model.AppLanguage
import com.yage.voiceflow.service.OpenCodeClient
import com.yage.voiceflowkit.VoiceFlowConfig

/**
 * Persistence for all VoiceFlow settings.
 *
 * Two backing stores:
 *  - ENCRYPTED ([EncryptedSharedPreferences] file `voiceflow_secure`): the AI
 *    Builder token and the OpenCode password — the only two secrets, matching
 *    iOS which keeps both in the Keychain.
 *  - PLAIN ([SharedPreferences] file `voiceflow_prefs`): OpenCode server URL,
 *    OpenCode username, transcription prompt, transcription terms, and the UI
 *    language preference. These are non-secret and on iOS live in UserDefaults.
 *
 * The AI Builder endpoint is NOT stored or editable: it is always
 * [VoiceFlowConfig.DEFAULT_ENDPOINT], mirroring iOS.
 *
 * BUG #1 FIX: [buildConfig] sets `prompt` to the RAW user "Context prompt" only
 * (trimmed; blank -> null) and `terms` to the comma-split list. The UI language
 * is NEVER read here and NEVER injected into the prompt. The old draft's
 * `effectivePrompt()` (which appended "User is speaking Mandarin Chinese." /
 * "User is speaking English.") is deleted entirely.
 *
 * The encrypted store can throw on a corrupted keystore (rare; e.g. after a
 * restore-from-backup with a mismatched key). Creation is wrapped so that on
 * failure the encrypted file is dropped and creation retried once with a fresh
 * key — losing the saved secrets is acceptable (the user re-enters them) and is
 * preferable to crashing on launch. `allowBackup=false` in the manifest keeps
 * the keystore and the encrypted file from drifting apart.
 */
class SettingsStore private constructor(
    private val secure: SharedPreferences,
    private val plain: SharedPreferences,
) {

    // --- AI Builder token (secret) ---

    fun getToken(): String = secure.getString(KEY_TOKEN, null).orEmpty()

    fun hasToken(): Boolean = getToken().isNotBlank()

    /** Trim + persist. Returns false if the encrypted write fails. */
    fun saveToken(token: String): Boolean = try {
        secure.edit().putString(KEY_TOKEN, token.trim()).commit()
    } catch (_: Throwable) {
        false
    }

    fun clearToken(): Boolean = try {
        secure.edit().remove(KEY_TOKEN).commit()
    } catch (_: Throwable) {
        false
    }

    /**
     * Masked display for a saved token. Matches iOS `tokenDisplayValue`:
     * a fixed `••••••••` when a token is stored, empty otherwise. (No last-4
     * suffix — the iOS app never reveals any token characters.)
     */
    fun tokenDisplay(): String = if (hasToken()) MASK else ""

    // --- OpenCode password (secret) ---

    fun getOpenCodePassword(): String = secure.getString(KEY_OPENCODE_PASSWORD, null).orEmpty()

    fun hasOpenCodePassword(): Boolean = getOpenCodePassword().isNotBlank()

    fun saveOpenCodePassword(password: String): Boolean = try {
        secure.edit().putString(KEY_OPENCODE_PASSWORD, password.trim()).commit()
    } catch (_: Throwable) {
        false
    }

    /** Clears ONLY the password (URL + username stay), mirroring iOS "Clear". */
    fun clearOpenCodePassword(): Boolean = try {
        secure.edit().remove(KEY_OPENCODE_PASSWORD).commit()
    } catch (_: Throwable) {
        false
    }

    /** Masked password display: `••••••••` when stored, empty otherwise. */
    fun openCodePasswordDisplay(): String = if (hasOpenCodePassword()) MASK else ""

    // --- OpenCode server URL + username (non-secret) ---

    var openCodeServerURL: String
        get() = plain.getString(KEY_OPENCODE_URL, null) ?: OpenCodeClient.DEFAULT_SERVER_URL
        set(value) {
            plain.edit().putString(KEY_OPENCODE_URL, value).apply()
        }

    var openCodeUsername: String
        get() = plain.getString(KEY_OPENCODE_USERNAME, null) ?: OpenCodeClient.DEFAULT_USERNAME
        set(value) {
            plain.edit().putString(KEY_OPENCODE_USERNAME, value).apply()
        }

    // --- Transcription settings (non-secret) ---

    /** RAW context prompt as typed by the user. Persisted verbatim. */
    var prompt: String
        get() = plain.getString(KEY_PROMPT, "").orEmpty()
        set(value) {
            plain.edit().putString(KEY_PROMPT, value).apply()
        }

    /** RAW comma-separated terms string as typed by the user. */
    var termsRaw: String
        get() = plain.getString(KEY_TERMS, "").orEmpty()
        set(value) {
            plain.edit().putString(KEY_TERMS, value).apply()
        }

    // --- UI language preference (non-secret; UI display ONLY — bug #1/#2) ---

    var language: AppLanguage
        get() = AppLanguage.fromRawValue(plain.getString(KEY_LANGUAGE, null))
        set(value) {
            plain.edit().putString(KEY_LANGUAGE, value.rawValue).apply()
        }

    // --- Config assembly ---

    /** Parse the raw terms string into a trimmed, non-empty list. */
    private fun parsedTerms(): List<String> =
        termsRaw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * Build a [VoiceFlowConfig] whose `tokenProvider` re-reads the encrypted
     * store on every call, so a token saved mid-session is observed by the next
     * request. The endpoint is fixed to the kit default (matches iOS).
     *
     * BUG #1 FIX: `prompt` is the RAW Settings prompt only (trimmed; blank =>
     * null) — never a language hint, never the placeholder text. `terms` is the
     * comma-split list.
     */
    fun buildConfig(): VoiceFlowConfig =
        VoiceFlowConfig(
            endpoint = VoiceFlowConfig.DEFAULT_ENDPOINT,
            tokenProvider = { getToken() },
            prompt = prompt.trim().ifBlank { null },
            terms = parsedTerms(),
        )

    companion object {
        const val MASK = "••••••••"

        private const val SECURE_FILE = "voiceflow_secure"
        private const val PLAIN_FILE = "voiceflow_prefs"

        private const val KEY_TOKEN = "ai_builder_token"
        private const val KEY_OPENCODE_PASSWORD = "opencode_password"
        private const val KEY_OPENCODE_URL = "opencode_server_url"
        private const val KEY_OPENCODE_USERNAME = "opencode_username"
        private const val KEY_PROMPT = "transcription_prompt"
        private const val KEY_TERMS = "transcription_terms"
        private const val KEY_LANGUAGE = "app_language"

        fun create(context: Context): SettingsStore {
            val appContext = context.applicationContext
            val plain = appContext.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)
            val secure = createSecurePrefs(appContext)
            return SettingsStore(secure = secure, plain = plain)
        }

        private fun createSecurePrefs(appContext: Context): SharedPreferences {
            return try {
                buildEncryptedPrefs(appContext)
            } catch (_: Throwable) {
                // Corrupted keystore / key mismatch: drop the file and retry once.
                appContext.deleteSharedPreferences(SECURE_FILE)
                try {
                    buildEncryptedPrefs(appContext)
                } catch (_: Throwable) {
                    // Last resort: fall back to a plain file so the app still
                    // launches. The secrets simply won't be encrypted on this
                    // (already unusual) device state.
                    appContext.getSharedPreferences(SECURE_FILE, Context.MODE_PRIVATE)
                }
            }
        }

        private fun buildEncryptedPrefs(appContext: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                appContext,
                SECURE_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
