# Consumer ProGuard/R8 rules applied to apps that depend on VoiceFlowKit.
# The public API is plain Kotlin classes with no reflection requirements, so no
# special keep rules are required today. Add rules here if future internals rely
# on reflection (e.g. for JSON mapping) so that consumers inherit them.
