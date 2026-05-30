package com.yage.voiceflow

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.yage.voiceflow.i18n.stringRes
import com.yage.voiceflow.i18n.stringResByKey
import com.yage.voiceflow.ui.components.PixelGearIcon
import com.yage.voiceflow.ui.components.PixelMicIcon
import com.yage.voiceflow.ui.record.RecordScreen
import com.yage.voiceflow.ui.settings.SettingsScreen
import com.yage.voiceflow.ui.theme.DesignTokens

private enum class Tab { Record, Settings }

/**
 * Root composable. A two-tab Material [NavigationBar] (Record / Settings) over
 * a [Scaffold], mirroring the iOS `MainTabView`. A plain `selectedTab` state is
 * used instead of navigation-compose since there are only two flat
 * destinations — switching tabs is a pure recomposition with no back stack.
 *
 * This composable hosts the three app-level dialogs so they survive tab
 * switches:
 *
 *  - The one-shot record-error dialog (bug #3 fix). Driven by
 *    [MainViewModel.recordErrorKey] — a dedicated `StateFlow<String?>` OUTSIDE
 *    `UiState`. The dialog shows iff the key is non-null; OK / dismiss calls
 *    [MainViewModel.dismissRecordError] which nils it exactly once. Switching
 *    tabs only recomposes; it never sets the key, and once acknowledged the key
 *    is already null, so the dialog cannot re-raise. Mirrors the iOS
 *    `recordErrorAlertKey` consumed-state model. The key is a dotted iOS string
 *    key, resolved through [stringResByKey].
 *
 *  - The saved-recording confirmation dialog, driven by
 *    `UiState.shouldPresentSavedRecordingAlert` + `lastSavedRecording`,
 *    acknowledged via [MainViewModel.acknowledgeSavedRecordingAlert].
 *
 *  - The "About OpenCode" info dialog (local, transient UI state).
 */
@Composable
fun VoiceFlowApp(viewModel: MainViewModel) {
    var selectedTab by remember { mutableStateOf(Tab.Record) }
    var showOpenCodeInfo by remember { mutableStateOf(false) }

    val recordErrorKey by viewModel.recordErrorKey.collectAsState()
    val state by viewModel.state.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == Tab.Record,
                    onClick = { selectedTab = Tab.Record },
                    icon = { PixelMicIcon(color = tabIconColor(selectedTab == Tab.Record)) },
                    label = { Text(stringRes(R.string.tab_record)) },
                    colors = navItemColors(),
                )
                NavigationBarItem(
                    selected = selectedTab == Tab.Settings,
                    onClick = { selectedTab = Tab.Settings },
                    icon = { PixelGearIcon(color = tabIconColor(selectedTab == Tab.Settings)) },
                    label = { Text(stringRes(R.string.tab_settings)) },
                    colors = navItemColors(),
                )
            }
        },
    ) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding)
        when (selectedTab) {
            Tab.Record -> RecordScreen(
                viewModel = viewModel,
                onShowOpenCodeInfo = { showOpenCodeInfo = true },
                modifier = contentModifier,
            )

            Tab.Settings -> SettingsScreen(
                viewModel = viewModel,
                modifier = contentModifier,
            )
        }
    }

    // --- One-shot record-error dialog (bug #3) ---
    val errorKey = recordErrorKey
    if (errorKey != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRecordError() },
            title = { Text(stringRes(R.string.record_error_alert_title)) },
            text = { Text(stringResByKey(errorKey)) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissRecordError() }) {
                    Text(stringRes(R.string.record_error_alert_ok))
                }
            },
        )
    }

    // --- Saved-recording confirmation dialog ---
    if (state.shouldPresentSavedRecordingAlert) {
        val fileName = state.lastSavedRecording?.fileName ?: ""
        AlertDialog(
            onDismissRequest = { viewModel.acknowledgeSavedRecordingAlert() },
            title = { Text(stringRes(R.string.record_save_confirmation_title)) },
            text = { Text(stringRes(R.string.record_save_confirmation_message, fileName)) },
            confirmButton = {
                TextButton(onClick = { viewModel.acknowledgeSavedRecordingAlert() }) {
                    Text(stringRes(R.string.record_error_alert_ok))
                }
            },
        )
    }

    // --- About OpenCode info dialog ---
    if (showOpenCodeInfo) {
        AlertDialog(
            onDismissRequest = { showOpenCodeInfo = false },
            title = { Text(stringRes(R.string.record_sendToOpenCode)) },
            text = { Text(stringRes(R.string.record_openCode_optional)) },
            confirmButton = {
                TextButton(onClick = { showOpenCodeInfo = false }) {
                    Text(stringRes(R.string.record_error_alert_ok))
                }
            },
        )
    }
}

@Composable
private fun navItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = DesignTokens.Palette.accent,
    selectedTextColor = DesignTokens.Palette.accent,
    indicatorColor = DesignTokens.Palette.accentMuted,
)

/**
 * Pixelate tab glyphs are drawn on a raw [androidx.compose.foundation.Canvas],
 * so they don't inherit [NavigationBarItemDefaults] icon tinting — colour is
 * resolved explicitly here: amber when selected, muted grey otherwise, matching
 * the one-accent-per-screen discipline.
 */
@Composable
private fun tabIconColor(selected: Boolean): Color =
    if (selected) DesignTokens.Palette.accent else DesignTokens.Palette.textTertiary
