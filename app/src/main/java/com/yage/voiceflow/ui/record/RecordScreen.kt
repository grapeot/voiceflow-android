package com.yage.voiceflow.ui.record

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yage.voiceflow.MainViewModel
import com.yage.voiceflow.R
import com.yage.voiceflow.UiState
import com.yage.voiceflow.i18n.stringRes
import com.yage.voiceflow.i18n.stringResByKey
import com.yage.voiceflow.model.OpenCodeSendStatus
import com.yage.voiceflow.model.RecordingStatus
import com.yage.voiceflow.ui.components.CapsuleButton
import com.yage.voiceflow.ui.components.CapsuleButtonRole
import com.yage.voiceflow.ui.components.GhostIconButton
import com.yage.voiceflow.ui.components.StatusText
import com.yage.voiceflow.ui.components.StatusTextRole
import com.yage.voiceflow.ui.components.WaveformMode
import com.yage.voiceflow.ui.components.WaveformView
import com.yage.voiceflow.ui.theme.DesignTokens
import com.yage.voiceflowkit.VoiceFlowConnectionPhase

/**
 * The Record tab. A faithful Material port of the iOS `RecordView`:
 *
 *  timer header (monospaced MM:SS) + [StatusText]
 *  → [WaveformView]
 *  → editable transcript field with a centered placeholder when empty
 *  → primary [CapsuleButton] Record ↔ Stop
 *  → secondary controls row: previous-history chevron + overflow menu
 *    (Copy / Send to OpenCode / Save Recording / Resend Recording) + next chevron.
 *
 * All state derivations (`waveformMode`, `waveformColor`, the status line, the
 * status role, the OpenCode menu label/icon) replicate the iOS `RecordView`
 * computed properties exactly. The one-shot error dialog and the
 * saved-recording confirmation dialog are hosted at the app root
 * ([com.yage.voiceflow.VoiceFlowApp]) so they survive tab switches (bug #3).
 *
 * The microphone permission grant is owned by the Activity and wired into the
 * ViewModel as a suspend requester ([MainViewModel.setMicPermissionRequester]),
 * so the Record button simply calls `startRecording()`; the VM awaits the grant
 * (mirroring iOS `audioRecorder.requestPermission()`) and surfaces a readable
 * error if it is denied.
 */
@Composable
fun RecordScreen(
    viewModel: MainViewModel,
    onShowOpenCodeInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DesignTokens.Palette.bgPrimary),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = DesignTokens.Spacing.l),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(DesignTokens.Spacing.xxl))

            // --- Timer header + status ---
            Text(
                text = state.recordingTimerText,
                color = DesignTokens.Palette.textPrimary,
                // Pixel hint pulled back: the MM:SS timer returns to the regular
                // 56sp Thin face. The pixel language now lives only in the
                // waveform, status caption, tab glyphs and logo.
                style = DesignTokens.Typography.timer,
            )
            Spacer(Modifier.height(DesignTokens.Spacing.s))
            StatusText(
                text = statusText(state),
                role = statusTextRole(state),
                modifier = Modifier.padding(horizontal = DesignTokens.Spacing.xl),
            )

            Spacer(Modifier.height(DesignTokens.Spacing.l))

            // --- Waveform ---
            WaveformView(
                mode = waveformMode(state.recordingStatus),
                color = waveformColor(state.recordingStatus, state.streamConnectionPhase),
                level = state.audioLevel,
                modifier = Modifier.padding(horizontal = DesignTokens.Spacing.xl),
            )

            Spacer(Modifier.height(DesignTokens.Spacing.xxl))

            // --- Editable transcript with centered placeholder ---
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = DesignTokens.Spacing.xl)
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center,
            ) {
                BasicTextField(
                    value = state.transcript,
                    onValueChange = viewModel::onTranscriptEdited,
                    textStyle = DesignTokens.Typography.body.copy(color = DesignTokens.Palette.textPrimary),
                    cursorBrush = SolidColor(DesignTokens.Palette.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.transcript.isEmpty()) {
                    // Centered to stay symmetric with the timer/waveform above;
                    // a left-aligned placeholder reads as orphaned (matches the
                    // iOS rationale in RecordView).
                    Text(
                        text = stringRes(R.string.record_transcript_placeholder),
                        color = DesignTokens.Palette.textTertiary,
                        textAlign = TextAlign.Center,
                        style = DesignTokens.Typography.body,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(DesignTokens.Spacing.l))

            // --- Primary action ---
            val isStop = state.canStopRecording
            CapsuleButton(
                title = stringRes(if (isStop) R.string.record_stop else R.string.record_start),
                role = if (isStop) CapsuleButtonRole.Secondary else CapsuleButtonRole.Primary,
                isEnabled = state.canStartRecording || state.canStopRecording,
                onClick = {
                    if (isStop) viewModel.stopRecording() else viewModel.startRecording()
                },
            )

            Spacer(Modifier.height(DesignTokens.Spacing.m))

            // --- Secondary controls ---
            SecondaryControls(
                viewModel = viewModel,
                state = state,
            )
        }
    }
}

@Composable
private fun SecondaryControls(
    viewModel: MainViewModel,
    state: UiState,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GhostIconButton(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            isEnabled = state.canNavigatePreviousTranscript,
            contentDescription = stringRes(R.string.record_history),
            onClick = { viewModel.navigatePreviousTranscript() },
        )

        Box {
            GhostIconButton(
                icon = Icons.Filled.MoreVert,
                isEnabled = true,
                contentDescription = stringRes(R.string.record_controls_title),
                onClick = { menuExpanded = true },
            )
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringRes(R.string.record_copy)) },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                    enabled = state.canCopyTranscript,
                    onClick = {
                        menuExpanded = false
                        viewModel.copyTranscript()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringRes(openCodeMenuLabelRes(state.openCodeSendStatus))) },
                    leadingIcon = {
                        Icon(openCodeMenuIcon(state.openCodeSendStatus), contentDescription = null)
                    },
                    enabled = state.canSendToOpenCode &&
                        state.openCodeSendStatus != OpenCodeSendStatus.Sending,
                    onClick = {
                        menuExpanded = false
                        viewModel.sendTranscriptToOpenCode()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringRes(R.string.record_saveRecording)) },
                    leadingIcon = { Icon(Icons.Outlined.SaveAlt, contentDescription = null) },
                    enabled = state.canSaveRecording,
                    onClick = {
                        menuExpanded = false
                        viewModel.saveCurrentRecording()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringRes(R.string.record_resendRecording)) },
                    leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    enabled = state.canResendRecording &&
                        state.recordingStatus != RecordingStatus.Transcribing,
                    onClick = {
                        menuExpanded = false
                        viewModel.resendLastRecording()
                    },
                )
            }
        }

        GhostIconButton(
            icon = Icons.AutoMirrored.Outlined.ArrowForward,
            isEnabled = state.canNavigateNextTranscript,
            contentDescription = stringRes(R.string.record_history),
            onClick = { viewModel.navigateNextTranscript() },
        )
    }
}

// --- State derivations (ports of RecordView computed properties) ---

private fun waveformMode(status: RecordingStatus): WaveformMode = when (status) {
    RecordingStatus.Recording -> WaveformMode.Active
    RecordingStatus.Transcribing -> WaveformMode.Generating
    else -> WaveformMode.Idle
}

@Composable
private fun waveformColor(
    status: RecordingStatus,
    phase: VoiceFlowConnectionPhase,
): Color = when (status) {
    RecordingStatus.Recording -> when (phase) {
        VoiceFlowConnectionPhase.Connected, VoiceFlowConnectionPhase.Generating -> DesignTokens.Palette.accent
        VoiceFlowConnectionPhase.Connecting, VoiceFlowConnectionPhase.Recovering -> DesignTokens.Palette.textSecondary
        VoiceFlowConnectionPhase.Disconnected -> DesignTokens.Palette.textTertiary
    }
    RecordingStatus.Transcribing -> DesignTokens.Palette.accent
    RecordingStatus.RequestingPermission -> DesignTokens.Palette.textSecondary
    RecordingStatus.Idle, RecordingStatus.Ready -> DesignTokens.Palette.textTertiary
}

/**
 * Resolves the status line text, replicating the iOS `statusTextKey` priority
 * chain: OpenCode send status → saved-recording status line → stream caption →
 * clipboard status → recording-status default. All but the save line are dotted
 * iOS keys resolved through [stringResByKey]; the save line carries the saved
 * file name as a format arg.
 */
@Composable
private fun statusText(state: UiState): String {
    if (state.openCodeSendStatus != OpenCodeSendStatus.Idle) {
        return stringResByKey(state.openCodeSendStatus.localizedKey)
    }
    state.lastSavedRecording?.let { saved ->
        return stringResByKey("record.save.statusLine", saved.fileName)
    }
    state.streamStatusCaptionKey?.let { return stringResByKey(it) }
    state.lastClipboardStatusKey?.let { return stringResByKey(it) }
    return stringResByKey(state.recordingStatus.statusKey)
}

private fun statusTextRole(state: UiState): StatusTextRole = when (state.recordingStatus) {
    RecordingStatus.Recording -> when (state.streamConnectionPhase) {
        VoiceFlowConnectionPhase.Connected, VoiceFlowConnectionPhase.Generating -> StatusTextRole.Accent
        VoiceFlowConnectionPhase.Connecting, VoiceFlowConnectionPhase.Recovering -> StatusTextRole.Neutral
        VoiceFlowConnectionPhase.Disconnected -> StatusTextRole.Muted
    }
    RecordingStatus.Transcribing -> StatusTextRole.Accent
    RecordingStatus.Idle, RecordingStatus.Ready, RecordingStatus.RequestingPermission -> StatusTextRole.Neutral
}

private fun openCodeMenuLabelRes(status: OpenCodeSendStatus): Int = when (status) {
    OpenCodeSendStatus.Sending -> R.string.record_openCode_sending
    OpenCodeSendStatus.Success -> R.string.record_openCode_sent
    is OpenCodeSendStatus.Failed -> R.string.record_openCode_error_sendFailed
    OpenCodeSendStatus.Idle -> R.string.record_sendToOpenCode
}

private fun openCodeMenuIcon(status: OpenCodeSendStatus) = when (status) {
    OpenCodeSendStatus.Sending -> Icons.Outlined.Send
    OpenCodeSendStatus.Success -> Icons.Outlined.CheckCircle
    is OpenCodeSendStatus.Failed -> Icons.Outlined.Warning
    OpenCodeSendStatus.Idle -> Icons.Outlined.Send
}
