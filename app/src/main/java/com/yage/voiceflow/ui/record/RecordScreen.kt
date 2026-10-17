package com.yage.voiceflow.ui.record

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yage.voiceflow.MainViewModel
import com.yage.voiceflow.R
import com.yage.voiceflow.UiState
import com.yage.voiceflow.i18n.stringRes
import com.yage.voiceflow.i18n.stringResByKey
import com.yage.voiceflow.model.OpenCodeSendStatus
import com.yage.voiceflow.model.RecordingStatus
import com.yage.voiceflow.model.TranscriptMode
import com.yage.voiceflow.model.CustomActionState
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
 *  → persistent custom-action/mode/clear/copy toolbar
 *  → primary [CapsuleButton] Record ↔ Stop
 *  → secondary controls row: previous-history chevron + overflow menu
 *    (Send to OpenCode / Save Recording / Resend Recording) + next chevron.
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
    val transcriptScrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val editorBounds = remember { mutableStateOf(Rect.Zero) }
    val rootCoordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
    var isEditing by remember { mutableStateOf(false) }
    var viewportPx by remember { mutableIntStateOf(0) }
    var measuredWarningPx by remember { mutableIntStateOf(0) }
    var previousTranscript by remember { mutableStateOf(state.transcript) }
    var pendingUserEdit by remember { mutableStateOf<String?>(null) }
    DisposableEffect(viewModel) { onDispose { viewModel.clearToolbarFeedback() } }

    LaunchedEffect(state.transcript) {
        val fromUser = pendingUserEdit != null && pendingUserEdit == state.transcript
        pendingUserEdit = null
        val action = transcriptScrollTarget(previousTranscript, state.transcript, fromUser)
        previousTranscript = state.transcript
        when (action) {
            TranscriptScrollTarget.End -> {
                withFrameNanos { }
                transcriptScrollState.scrollTo(transcriptScrollState.maxValue)
            }
            TranscriptScrollTarget.Start -> {
                withFrameNanos { }
                transcriptScrollState.scrollTo(0)
            }
            TranscriptScrollTarget.None -> Unit
        }
    }

    val topGap by animateDpAsState(compactSpacing(isEditing, DesignTokens.Spacing.xxl), CompactTween, label = "topGap")
    val statusGap by animateDpAsState(compactSpacing(isEditing, DesignTokens.Spacing.l), CompactTween, label = "statusGap")
    val transcriptGap by animateDpAsState(compactSpacing(isEditing, DesignTokens.Spacing.xxl), CompactTween, label = "transcriptGap")
    val waveformHeight by animateDpAsState(if (isEditing) 0.dp else DesignTokens.Sizing.waveformHeight, CompactTween, label = "waveformHeight")
    val waveformAlpha by animateFloatAsState(if (isEditing) 0f else 1f, CompactFloatTween, label = "waveformAlpha")
    val timerSize by animateFloatAsState(
        targetValue = if (isEditing) DesignTokens.Typography.captionSub.fontSize.value else DesignTokens.Typography.timer.fontSize.value,
        animationSpec = CompactFloatTween,
        label = "timerSize",
    )
    val density = LocalDensity.current
    val warningPx = if (state.showTranscriptWarning) measuredWarningPx else 0
    val minField = with(density) { (viewportPx - warningPx).coerceAtLeast(0).toDp() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DesignTokens.Palette.bgPrimary)
            .onGloballyPositioned { rootCoordinates.value = it }
            .clearFocusWhenTappedOutsideEditor(
                focusManager = focusManager,
                editorBounds = { editorBounds.value },
                root = { rootCoordinates.value },
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = DesignTokens.Spacing.l),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(topGap))

            Text(
                text = state.recordingTimerText,
                color = DesignTokens.Palette.textPrimary,
                style = (if (isEditing) DesignTokens.Typography.captionSub else DesignTokens.Typography.timer)
                    .copy(fontSize = timerSize.sp),
            )
            Spacer(Modifier.height(DesignTokens.Spacing.s))
            StatusText(
                text = statusText(state),
                role = statusTextRole(state),
                modifier = Modifier.padding(horizontal = DesignTokens.Spacing.xl),
                minLines = 2,
            )

            Spacer(Modifier.height(statusGap))

            Box(
                modifier = Modifier
                    .padding(horizontal = DesignTokens.Spacing.xl)
                    .height(waveformHeight)
                    .alpha(waveformAlpha)
                    .clipToBounds(),
            ) {
                WaveformView(
                    mode = waveformMode(state.recordingStatus),
                    color = waveformColor(state.recordingStatus, state.streamConnectionPhase),
                    level = state.audioLevel,
                )
            }

            Spacer(Modifier.height(transcriptGap))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { viewportPx = it.height }
                    .verticalScroll(transcriptScrollState)
                    .padding(horizontal = DesignTokens.Spacing.xl),
            ) {
                if (state.showTranscriptWarning) {
                    Text(
                        text = stringRes(R.string.record_signal_shortRecording),
                        color = DesignTokens.Palette.textSecondary,
                        style = DesignTokens.Typography.caption,
                        modifier = Modifier
                            .onSizeChanged { measuredWarningPx = it.height }
                            .fillMaxWidth()
                            .padding(bottom = DesignTokens.Spacing.s),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = minField)
                        .onGloballyPositioned { editorBounds.value = it.boundsInWindow() }
                        .focusOnUnconsumedTap(focusRequester),
                ) {
                    BasicTextField(
                        value = state.transcript,
                        onValueChange = { value ->
                            if (value != state.transcript) pendingUserEdit = value
                            viewModel.onTranscriptEdited(value)
                        },
                        readOnly = state.customActionRunning || state.chunkLocked ||
                            state.recordingStatus == RecordingStatus.RequestingPermission,
                        textStyle = DesignTokens.Typography.body.copy(color = DesignTokens.Palette.textPrimary),
                        cursorBrush = SolidColor(DesignTokens.Palette.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
                        singleLine = false,
                        maxLines = Int.MAX_VALUE,
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopStart)
                            .focusRequester(focusRequester)
                            .onFocusChanged { isEditing = it.isFocused },
                    )
                    if (state.transcript.isEmpty()) {
                        Text(
                            text = stringRes(R.string.record_transcript_placeholder),
                            color = DesignTokens.Palette.textTertiary,
                            textAlign = TextAlign.Center,
                            style = DesignTokens.Typography.body,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth(),
                        )
                    }
                }
            }

            TranscriptToolbar(state, viewModel::runOrCancelCustomAction, viewModel::toggleTranscriptMode,
                viewModel::clearTranscriptToHistory, viewModel::copyTranscriptFromToolbar)

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

private val appendTextIcon = ImageVector.Builder("AppendText", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 6f); lineTo(3f, 6f); close()
        moveTo(3f, 9f); lineTo(17f, 9f); lineTo(17f, 11f); lineTo(3f, 11f); close()
        moveTo(3f, 14f); lineTo(12f, 14f); lineTo(12f, 16f); lineTo(3f, 16f); close()
        moveTo(17f, 13f); lineTo(19f, 13f); lineTo(19f, 16f); lineTo(22f, 16f)
        lineTo(22f, 18f); lineTo(19f, 18f); lineTo(19f, 21f); lineTo(17f, 21f)
        lineTo(17f, 18f); lineTo(14f, 18f); lineTo(14f, 16f); lineTo(17f, 16f); close()
    }
}.build()

@Composable
internal fun TranscriptToolbar(
    state: UiState,
    onAction: () -> Unit,
    onMode: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Boolean,
) {
    val actionRunning = state.customActionRunning
    val actionEnabled = actionRunning || state.canRunCustomAction
    val actionName = state.customActionConfig.name.trim().ifEmpty { stringRes(R.string.record_customAction_defaultName) }
    val isAppend = state.transcriptMode == TranscriptMode.Append
    val modeName = stringRes(if (isAppend) R.string.record_transcriptMode_append else R.string.record_transcriptMode_replace)
    val switchHint = stringRes(if (isAppend) R.string.record_transcriptMode_switchReplace else R.string.record_transcriptMode_switchAppend)
    var copied by remember { mutableStateOf(false) }
    var copyTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(copyTick) {
        if (copyTick > 0) {
            delay(1_200)
            copied = false
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = DesignTokens.Spacing.xl).testTag("record.transcriptToolbar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onAction,
            enabled = actionEnabled,
            modifier = Modifier.weight(1f).height(48.dp).testTag("record.customActionButton")
                .alpha(if (actionEnabled) 1f else 0.6f),
        ) {
            if (actionRunning) {
                CircularProgressIndicator(Modifier.size(18.dp), color = DesignTokens.Palette.textSecondary, strokeWidth = 2.dp)
            } else {
                Icon(
                    if (state.customActionState is CustomActionState.Failed) Icons.Outlined.Warning else Icons.Outlined.AutoFixHigh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = DesignTokens.Palette.textSecondary,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                if (actionRunning) stringRes(R.string.record_customAction_cancel) else actionName,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = DesignTokens.Typography.caption, color = DesignTokens.Palette.textSecondary,
            )
        }
        IconButton(
            onClick = onMode,
            enabled = state.canChangeTranscriptMode,
            modifier = Modifier.size(48.dp).testTag("record.transcriptModeButton")
                .semantics { stateDescription = modeName }
                .alpha(if (state.canChangeTranscriptMode) 1f else 0.6f),
        ) {
            Icon(
                if (isAppend) appendTextIcon else Icons.Outlined.SwapHoriz,
                stringRes(R.string.record_transcriptMode_title) + if (state.canChangeTranscriptMode) ": $switchHint" else "",
                tint = if (!state.canChangeTranscriptMode) DesignTokens.Palette.textTertiary
                    else if (isAppend) DesignTokens.Palette.accent else DesignTokens.Palette.textSecondary,
            )
        }
        IconButton(onClick = onClear, enabled = state.canClearTranscript,
            modifier = Modifier.size(48.dp).testTag("record.clearTranscriptButton")) {
            Icon(Icons.Outlined.DeleteOutline, stringRes(R.string.record_clear_title),
                tint = if (state.canClearTranscript) DesignTokens.Palette.textSecondary else DesignTokens.Palette.textTertiary)
        }
        IconButton(onClick = {
            copied = onCopy()
            copyTick++
        }, modifier = Modifier.size(48.dp).testTag("record.copyButton")) {
            Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                stringRes(R.string.record_copy), tint = DesignTokens.Palette.textSecondary)
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
            icon = if (state.transcriptMode == TranscriptMode.Append) Icons.AutoMirrored.Outlined.Undo else Icons.AutoMirrored.Outlined.ArrowBack,
            isEnabled = state.canNavigatePreviousTranscript,
            contentDescription = stringRes(if (state.transcriptMode == TranscriptMode.Append) R.string.record_undo else R.string.record_history),
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
                    text = { Text(stringRes(openCodeMenuLabelRes(state.openCodeSendStatus))) },
                    leadingIcon = {
                        Icon(openCodeMenuIcon(state.openCodeSendStatus), contentDescription = null)
                    },
                    enabled = state.canSendToOpenCode,
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
                    enabled = state.canResendRecording,
                    onClick = {
                        menuExpanded = false
                        viewModel.resendLastRecording()
                    },
                )
            }
        }

        GhostIconButton(
            icon = if (state.transcriptMode == TranscriptMode.Append) Icons.AutoMirrored.Outlined.Redo else Icons.AutoMirrored.Outlined.ArrowForward,
            isEnabled = state.canNavigateNextTranscript,
            contentDescription = stringRes(if (state.transcriptMode == TranscriptMode.Append) R.string.record_redo else R.string.record_history),
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
 * chain: active work → temporary toolbar feedback → previous completion/save
 * status → stream caption → clipboard status → recording-status default.
 * All but the save line are dotted
 * iOS keys resolved through [stringResByKey]; the save line carries the saved
 * file name as a format arg.
 */
@Composable
private fun statusText(state: UiState): String {
    val key = recordStatusKey(state)
    return if (key == "record.save.statusLine" && state.lastSavedRecording != null) {
        stringResByKey(key, state.lastSavedRecording.fileName)
    } else if (key == "record.customAction.running" && state.customActionState is CustomActionState.Running) {
        stringResByKey(key, state.customActionState.name)
    } else stringResByKey(key)
}

/** The final status selection, shared with regression tests (including fallback). */
internal fun recordStatusKey(state: UiState): String {
    if (state.recordingStatus != RecordingStatus.Idle && state.recordingStatus != RecordingStatus.Ready) {
        return state.streamStatusCaptionKey ?: state.recordingStatus.statusKey
    }
    if (state.customActionRunning) return "record.customAction.running"
    recordStatusOverrideKey(state)?.let { return it }
    (state.customActionState as? CustomActionState.Failed)?.let { return it.reason.key }
    if (state.openCodeSendStatus != OpenCodeSendStatus.Idle) {
        return state.openCodeSendStatus.localizedKey
    }
    if (state.lastSavedRecording != null) return "record.save.statusLine"
    return state.streamStatusCaptionKey ?: state.lastClipboardStatusKey ?: state.recordingStatus.statusKey
}

/** Only quiescent states accept transient toolbar feedback. */
internal fun recordStatusOverrideKey(state: UiState): String? {
    if (state.customActionRunning) return null
    if (state.recordingStatus != RecordingStatus.Idle && state.recordingStatus != RecordingStatus.Ready) return null
    if (state.openCodeSendStatus == OpenCodeSendStatus.Sending) return null
    return state.toolbarFeedbackKey
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

private val CompactEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val CompactTween = tween<Dp>(durationMillis = 300, easing = CompactEasing)
private val CompactFloatTween = tween<Float>(durationMillis = 300, easing = CompactEasing)

private fun compactSpacing(editing: Boolean, expanded: Dp): Dp =
    if (editing) DesignTokens.Spacing.s else expanded

private fun Modifier.clearFocusWhenTappedOutsideEditor(
    focusManager: FocusManager,
    editorBounds: () -> Rect,
    root: () -> LayoutCoordinates?,
): Modifier = pointerInput(focusManager) {
    val slop = viewConfiguration.touchSlop
    awaitPointerEventScope {
        var origin: Offset? = null
        var moved = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            for (change in event.changes) {
                if (change.changedToDownIgnoreConsumed()) {
                    origin = change.position
                    moved = false
                }
                val start = origin
                if (start != null && (change.position - start).getDistance() > slop) moved = true
                if (change.changedToUpIgnoreConsumed()) {
                    val startPos = origin
                    val wasTap = startPos != null && !moved
                    origin = null
                    moved = false
                    if (!wasTap) continue
                    val coordinates = root() ?: continue
                    if (!coordinates.isAttached) continue
                    val bounds = editorBounds()
                    if (bounds.isEmpty) continue
                    if (!bounds.contains(coordinates.localToWindow(change.position))) {
                        focusManager.clearFocus(force = true)
                    }
                }
            }
        }
    }
}

private fun Modifier.focusOnUnconsumedTap(focusRequester: FocusRequester): Modifier =
    pointerInput(focusRequester) {
        val slop = viewConfiguration.touchSlop
        awaitPointerEventScope {
            var origin: Offset? = null
            var moved = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                for (change in event.changes) {
                    if (change.changedToDownIgnoreConsumed()) {
                        origin = change.position
                        moved = false
                    }
                    val start = origin
                    if (start != null && (change.position - start).getDistance() > slop) moved = true
                    if (change.changedToUpIgnoreConsumed()) {
                        val wasTap = origin != null && !moved && !change.isConsumed
                        origin = null
                        moved = false
                        if (wasTap) focusRequester.requestFocus()
                    }
                }
            }
        }
    }
