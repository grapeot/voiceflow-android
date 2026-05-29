package com.yage.voiceflowkit.internal

import java.net.URI

/**
 * Builds REST and WebSocket URLs for the realtime backend.
 *
 * Port of Swift `RealtimeAPIURLBuilder`, reconciled with the proven Android reference
 * `AIBuildersAudioClient` (which uses `java.net.URI`). The URI-based reconstruction is the
 * confirmed-working mechanism on Android, so it is preserved here. The Swift relative-path
 * prefixing for a `ws_url` that begins with `/` but does not already contain the base path is
 * retained so backends that return a bare `/realtime?ticket=...` still resolve correctly.
 */
internal object RealtimeApiUrlBuilder {
    /**
     * Normalizes a user-supplied base URL: trims whitespace, prefixes `https://` when no scheme
     * is present, and validates that a host is present.
     *
     * @throws RealtimeTranscriptionError.InvalidBaseUrl if blank or hostless.
     */
    fun normalizedBaseURL(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw RealtimeTranscriptionError.InvalidBaseUrl
        }
        val normalized =
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
            else "https://$trimmed"
        val host = runCatching { URI(normalized).host }.getOrNull()
        if (host.isNullOrEmpty()) {
            throw RealtimeTranscriptionError.InvalidBaseUrl
        }
        return normalized
    }

    /**
     * Resolves [path] against [base], normalizing a trailing slash on the base path so the
     * resolve does not drop the base's path segment (e.g. `/backend`).
     *
     * Matches the proven Android reference exactly.
     */
    fun buildAPIURL(base: String, path: String): String {
        val relativePath = path.removePrefix("/")
        val baseUri = URI(base)
        var basePath = baseUri.path ?: ""
        if (basePath.isNotEmpty() && !basePath.endsWith("/")) {
            basePath += "/"
        }
        val baseForAppend = URI(
            baseUri.scheme,
            baseUri.authority,
            basePath,
            null,
            null,
        )
        return baseForAppend.resolve(relativePath).toString()
    }

    /**
     * Resolves [relativePath] (the server-returned `ws_url`, possibly relative) against [base] and
     * swaps the scheme to `wss`/`ws`, preserving the ticket query.
     *
     * If [relativePath] is absolute (`/...`) but does not already start with the base's path
     * segment, the base path is prefixed first (Swift behavior) so a bare `/realtime` reaches
     * `/backend/realtime`.
     */
    fun realtimeWebSocketURL(base: String, relativePath: String): String {
        val effectivePath = run {
            if (!relativePath.startsWith("/")) return@run relativePath
            val basePath = runCatching { URI(normalizedBaseURL(base)).path }.getOrNull().orEmpty()
            if (basePath.isNotEmpty() &&
                basePath != "/" &&
                !relativePath.startsWith("$basePath/")
            ) {
                basePath + relativePath
            } else {
                relativePath
            }
        }

        // Resolve the effective path against the normalized base. When [effectivePath] is an
        // absolute path (begins with `/`) URI.resolve replaces the base path entirely — matching
        // Swift's `URL(string:relativeTo:)` semantics — so a base-prefixed `/backend/realtime`
        // yields a single `/backend` segment rather than being re-appended under it. When it is a
        // bare relative path it falls back to [buildAPIURL]'s base-path-preserving resolve.
        val normalizedBase = normalizedBaseURL(base)
        val httpURL =
            if (effectivePath.startsWith("/")) {
                URI(normalizedBase).resolve(effectivePath)
            } else {
                URI(buildAPIURL(normalizedBase, effectivePath))
            }
        val webSocketScheme = when (httpURL.scheme) {
            "https" -> "wss"
            "http" -> "ws"
            else -> httpURL.scheme
        }

        return URI(
            webSocketScheme,
            httpURL.userInfo,
            httpURL.host,
            httpURL.port,
            httpURL.path,
            httpURL.query,
            httpURL.fragment,
        ).toString()
    }
}
