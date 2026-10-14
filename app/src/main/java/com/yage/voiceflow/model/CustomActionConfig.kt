package com.yage.voiceflow.model

data class CustomActionConfig(
    val name: String,
    val instructions: String,
    val model: String = "grok-4-fast",
) {
    val isConfigured: Boolean
        get() = name.isNotBlank() && instructions.isNotBlank() && models.containsKey(model)

    companion object {
        val models = linkedMapOf(
            "deepseek-v4-flash" to "DeepSeek V4 Flash",
            "grok-4-fast" to "Grok 4.3 (non-reasoning)",
        )

        fun default(chinese: Boolean) = CustomActionConfig(
            name = if (chinese) "润色" else "Polish",
            instructions = if (chinese) {
                "改写转写文本，使其更清晰。去掉口头禅和重复语句，在合适处分段，保留原意、语言、语气和细节。只返回改写后的文本。"
            } else {
                "Rewrite the transcript for clarity. Remove filler and repeated phrases, " +
                    "add paragraph breaks where useful, and preserve the original meaning, " +
                    "language, tone, and level of detail. Return only the revised text."
            },
        )
    }
}

/** Only these categories cross into UI state; never server bodies or exception text. */
enum class CustomActionFailure(val key: String) {
    NotConfigured("record.customAction.error.notConfigured"),
    MissingToken("record.customAction.error.missingToken"),
    Network("record.customAction.error.network"),
    Unauthorized("record.customAction.error.unauthorized"),
    Forbidden("record.customAction.error.forbidden"),
    RateLimited("record.customAction.error.rateLimited"),
    Server("record.customAction.error.serverError"),
    Request("record.customAction.error.requestFailed"),
    InvalidResponse("record.customAction.error.invalidResponse"),
    EmptyResult("record.customAction.error.emptyResult"),
    Truncated("record.customAction.error.truncated"),
    Generic("record.customAction.error.generic"),
}

sealed interface CustomActionState {
    data object Idle : CustomActionState
    data class Running(val name: String) : CustomActionState
    data class Failed(val reason: CustomActionFailure) : CustomActionState
}
