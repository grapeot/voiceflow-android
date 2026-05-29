package com.yage.voiceflow

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yage.voiceflow.i18n.LocalAppLocale
import com.yage.voiceflow.i18n.localizedContext
import com.yage.voiceflow.model.DeepLink
import com.yage.voiceflow.model.DeepLinkAction
import com.yage.voiceflow.ui.theme.VoiceFlowTheme
import kotlinx.coroutines.CompletableDeferred

/**
 * The single Activity host. Three responsibilities, mirroring the iOS app entry
 * point (`VoiceFlowApp` scene + `MainTabView`):
 *
 *  1. Owns the RECORD_AUDIO permission launcher. The library cannot prompt, so
 *     the grant must resolve here BEFORE [MainViewModel] starts the microphone.
 *     [ensureMicPermission] bridges the callback-based ActivityResult API into a
 *     suspend function, and is wired into the ViewModel via
 *     [MainViewModel.setMicPermissionRequester] so the VM's `startRecording`
 *     can `await` the grant exactly where iOS calls
 *     `audioRecorder.requestPermission()`.
 *
 *  2. Provides the language-aware [LocalAppLocale] context so every `stringRes`
 *     call re-localizes the instant the in-app language picker changes — the
 *     real in-app UI language switch (no device-language change, bug #2). When
 *     the persisted [com.yage.voiceflow.model.AppLanguage] in state changes, the
 *     provided Context instance changes and all visible text recomposes.
 *
 *  3. Handles the `voiceflow://record` deep link on both cold launch
 *     (`onCreate` intent) and warm delivery (`onNewIntent`), forwarding to the
 *     ViewModel which selects the Record tab and starts recording.
 */
class MainActivity : ComponentActivity() {

    private var pendingPermission: CompletableDeferred<Boolean>? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        pendingPermission?.complete(granted)
        pendingPermission = null
    }

    /**
     * The same [MainViewModel] the composition binds to. Resolved through this
     * Activity's ViewModelStore so `onNewIntent` (which can fire outside a
     * composable scope) reuses the exact instance `viewModel(...)` returns.
     */
    private val viewModel: MainViewModel by lazy {
        ViewModelProvider(this, MainViewModel.Factory)[MainViewModel::class.java]
    }

    /**
     * Suspends until the RECORD_AUDIO grant resolves. Returns true immediately
     * if already granted; otherwise launches the system prompt and awaits the
     * user's choice. A previously pending request is resolved false so a new
     * launch never strands an old awaiter.
     */
    suspend fun ensureMicPermission(): Boolean {
        if (hasMicPermission()) return true
        pendingPermission?.complete(false)
        val deferred = CompletableDeferred<Boolean>()
        pendingPermission = deferred
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        return deferred.await()
    }

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Give the VM a way to prompt for RECORD_AUDIO via this Activity's
        // launcher (the VM/kit cannot show the system prompt themselves).
        viewModel.setMicPermissionRequester { ensureMicPermission() }

        setContent {
            val vm: MainViewModel = viewModel(factory = MainViewModel.Factory)
            val state by vm.state.collectAsState()
            // Re-derive the localized Context whenever the language preference
            // changes; CompositionLocalProvider hands the new Context to every
            // stringRes() reader, flipping all visible text immediately (bug #2).
            CompositionLocalProvider(
                LocalAppLocale provides localizedContext(this, state.language),
            ) {
                VoiceFlowTheme {
                    VoiceFlowApp(viewModel = vm)
                }
            }
        }

        // Process a deep link delivered with the launching intent.
        handleDeepLinkIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (DeepLink.parse(data) == DeepLinkAction.StartRecording) {
            // Consume so a configuration change / re-create doesn't re-fire it.
            intent.data = null
            viewModel.handleDeepLinkStartRecording()
        }
    }
}
