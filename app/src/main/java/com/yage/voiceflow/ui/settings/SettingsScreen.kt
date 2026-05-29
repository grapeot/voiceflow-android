package com.yage.voiceflow.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yage.voiceflow.MainViewModel
import com.yage.voiceflow.R
import com.yage.voiceflow.i18n.stringRes
import com.yage.voiceflow.i18n.stringResByKey
import com.yage.voiceflow.model.AppLanguage
import com.yage.voiceflow.model.ConnectionTestStatus
import com.yage.voiceflow.ui.theme.DesignTokens
import com.yage.voiceflowkit.VoiceFlowConfig

/**
 * The Settings tab. Faithful Material port of the iOS `SettingsView`, section
 * for section:
 *
 *  - AI Builder Space: token field (masked "••••••••" + Saved/Not saved when
 *    stored, password input otherwise), read-only fixed endpoint, Save / Clear,
 *    Test connection + [ConnectionTestStatus] display, security hint.
 *  - Transcription: Context prompt (multiline) + Terms (multiline). These feed
 *    `VoiceFlowConfig.prompt` (RAW user text only) and `terms`. The placeholder
 *    is a hint only and is never persisted or sent (bug #1).
 *  - OpenCode: Server URL + Username (persisted on change), Password (masked
 *    input + Save / Clear), Test connection + status, optional hint.
 *  - Language: a segmented control System / English / 简体中文 that drives the
 *    real in-app UI language switch (bug #2).
 *
 * iOS renders these in a grouped `Form`; on Android each section is a Material
 * [Card] inside a [LazyColumn], the idiomatic equivalent.
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var tokenInput by rememberSaveable { mutableStateOf("") }
    var passwordInput by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DesignTokens.Palette.bgPrimary),
        contentPadding = PaddingValues(
            horizontal = DesignTokens.Spacing.m,
            vertical = DesignTokens.Spacing.m,
        ),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.m),
    ) {
        item {
            Text(
                stringRes(R.string.tab_settings),
                style = MaterialTheme.typography.headlineSmall,
                color = DesignTokens.Palette.textPrimary,
            )
        }

        // --- AI Builder Space ---
        item {
            SettingsSection(title = stringRes(R.string.settings_aiBuilder_title)) {
                Text(
                    stringRes(R.string.settings_apiToken_placeholder),
                    style = DesignTokens.Typography.bodyBold,
                    color = DesignTokens.Palette.textPrimary,
                )
                if (state.hasToken) {
                    MaskedValue(state.tokenDisplay)
                } else {
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        placeholder = { Text(stringRes(R.string.settings_apiToken_placeholder)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                LabeledValue(
                    label = stringRes(R.string.settings_endpoint_title),
                    value = VoiceFlowConfig.DEFAULT_ENDPOINT,
                )

                StatusRow(
                    label = stringRes(R.string.settings_apiToken_status),
                    value = stringRes(
                        if (state.hasToken) R.string.settings_apiToken_saved
                        else R.string.settings_apiToken_notSaved,
                    ),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val canSaveToken = !state.hasToken && tokenInput.isNotBlank()
                    TextButton(
                        onClick = {
                            viewModel.saveAIBuilderToken(tokenInput)
                            tokenInput = ""
                        },
                        enabled = canSaveToken,
                    ) {
                        Text(
                            stringRes(R.string.settings_apiToken_save),
                            color = accentIfEnabled(canSaveToken),
                        )
                    }
                    TextButton(
                        onClick = {
                            viewModel.clearAIBuilderToken()
                            tokenInput = ""
                        },
                        enabled = state.hasToken,
                    ) {
                        Text(
                            stringRes(R.string.settings_apiToken_clear),
                            color = DesignTokens.Palette.textSecondary,
                        )
                    }
                }

                val canTestToken = state.hasToken && state.connectionStatus != ConnectionTestStatus.Testing
                TextButton(
                    onClick = { viewModel.testAIBuilderConnection() },
                    enabled = canTestToken,
                ) {
                    Text(stringRes(R.string.settings_testConnection), color = accentIfEnabled(canTestToken))
                }

                ConnectionStatusView(state.connectionStatus, openCode = false)

                Text(
                    stringRes(R.string.settings_apiToken_securityHint),
                    style = DesignTokens.Typography.captionSub,
                    color = DesignTokens.Palette.textTertiary,
                )
            }
        }

        // --- Transcription ---
        item {
            SettingsSection(title = stringRes(R.string.settings_transcription_title)) {
                Text(
                    stringRes(R.string.settings_transcription_description),
                    style = DesignTokens.Typography.captionSub,
                    color = DesignTokens.Palette.textTertiary,
                )
                FieldLabel(stringRes(R.string.settings_transcription_prompt))
                OutlinedTextField(
                    value = state.prompt,
                    onValueChange = viewModel::updatePrompt,
                    placeholder = { Text(stringRes(R.string.settings_transcription_prompt_placeholder)) },
                    minLines = 2,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                FieldLabel(stringRes(R.string.settings_transcription_terms))
                OutlinedTextField(
                    value = state.terms,
                    onValueChange = viewModel::updateTerms,
                    placeholder = { Text(stringRes(R.string.settings_transcription_terms_placeholder)) },
                    minLines = 2,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // --- OpenCode ---
        item {
            SettingsSection(title = stringRes(R.string.settings_openCode_title)) {
                FieldLabel(stringRes(R.string.settings_openCode_serverURL))
                OutlinedTextField(
                    value = state.openCodeServerURL,
                    onValueChange = viewModel::updateOpenCodeServerURL,
                    placeholder = { Text(stringRes(R.string.settings_openCode_serverURL)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Uri,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                FieldLabel(stringRes(R.string.settings_openCode_username))
                OutlinedTextField(
                    value = state.openCodeUsername,
                    onValueChange = viewModel::updateOpenCodeUsername,
                    placeholder = { Text(stringRes(R.string.settings_openCode_username)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth(),
                )

                FieldLabel(stringRes(R.string.settings_openCode_password))
                if (state.hasSavedOpenCodePassword) {
                    MaskedValue(state.openCodePasswordDisplay)
                } else {
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        placeholder = { Text(stringRes(R.string.settings_openCode_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                StatusRow(
                    label = stringRes(R.string.settings_openCode_status),
                    value = stringRes(
                        if (state.isOpenCodeConfigured) R.string.settings_openCode_configured
                        else R.string.settings_openCode_notConfigured,
                    ),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val canSavePassword = !state.hasSavedOpenCodePassword &&
                        passwordInput.isNotBlank() &&
                        state.openCodeServerURL.isNotBlank() &&
                        state.openCodeUsername.isNotBlank()
                    TextButton(
                        onClick = {
                            viewModel.saveOpenCodePassword(passwordInput)
                            passwordInput = ""
                        },
                        enabled = canSavePassword,
                    ) {
                        Text(
                            stringRes(R.string.settings_openCode_save),
                            color = accentIfEnabled(canSavePassword),
                        )
                    }
                    TextButton(
                        onClick = {
                            viewModel.clearOpenCodePassword()
                            passwordInput = ""
                        },
                        enabled = state.hasSavedOpenCodePassword,
                    ) {
                        Text(
                            stringRes(R.string.settings_openCode_clear),
                            color = DesignTokens.Palette.textSecondary,
                        )
                    }
                }

                val canTestOpenCode = state.isOpenCodeConfigured &&
                    state.openCodeConnectionStatus != ConnectionTestStatus.Testing
                TextButton(
                    onClick = { viewModel.testOpenCodeConnection() },
                    enabled = canTestOpenCode,
                ) {
                    Text(stringRes(R.string.settings_openCode_testConnection), color = accentIfEnabled(canTestOpenCode))
                }

                ConnectionStatusView(state.openCodeConnectionStatus, openCode = true)

                Text(
                    stringRes(R.string.settings_openCode_optionalHint),
                    style = DesignTokens.Typography.captionSub,
                    color = DesignTokens.Palette.textTertiary,
                )
            }
        }

        // --- Language ---
        item {
            SettingsSection(title = stringRes(R.string.settings_language_title)) {
                Text(
                    stringRes(R.string.settings_language_description),
                    style = DesignTokens.Typography.captionSub,
                    color = DesignTokens.Palette.textTertiary,
                )
                val options = AppLanguage.entries
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, language ->
                        SegmentedButton(
                            selected = state.language == language,
                            onClick = { viewModel.updateLanguage(language) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        ) {
                            Text(stringResByKey(language.titleKey))
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(DesignTokens.Spacing.l)) }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DesignTokens.Palette.bgSecondary),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DesignTokens.Spacing.m),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.s),
        ) {
            Text(title, style = DesignTokens.Typography.bodyBold, color = DesignTokens.Palette.textPrimary)
            content()
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = DesignTokens.Typography.captionSub, color = DesignTokens.Palette.textSecondary)
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
        Text(label, style = DesignTokens.Typography.captionSub, color = DesignTokens.Palette.textSecondary)
        Text(value, style = DesignTokens.Typography.caption, color = DesignTokens.Palette.textTertiary)
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = DesignTokens.Typography.captionSub, color = DesignTokens.Palette.textSecondary)
        Text(value, style = DesignTokens.Typography.caption, color = DesignTokens.Palette.textSecondary)
    }
}

@Composable
private fun MaskedValue(value: String) {
    Text(
        text = value,
        style = DesignTokens.Typography.body.copy(fontFamily = FontFamily.Monospace),
        color = DesignTokens.Palette.textSecondary,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Renders a [ConnectionTestStatus] line, mirroring the iOS
 * `connectionStatusView`. Uses the AI-Builder key set or the OpenCode key set
 * per [openCode] (the model exposes `localizedKey` vs `openCodeLocalizedKey`,
 * both dotted iOS keys). A failed status colors the message accent and appends
 * its optional detail line.
 */
@Composable
private fun ConnectionStatusView(status: ConnectionTestStatus, openCode: Boolean) {
    val messageKey = if (openCode) status.openCodeLocalizedKey else status.localizedKey
    val detail = status.detailOrNull
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
        Text(
            text = stringResByKey(messageKey),
            style = DesignTokens.Typography.captionSub,
            color = if (detail != null) DesignTokens.Palette.accent else DesignTokens.Palette.textSecondary,
        )
        if (detail != null) {
            Text(detail, style = DesignTokens.Typography.captionSub, color = DesignTokens.Palette.accent)
        }
    }
}

@Composable
private fun accentIfEnabled(enabled: Boolean): Color =
    if (enabled) DesignTokens.Palette.accent else DesignTokens.Palette.textTertiary
