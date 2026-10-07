package dev.mstaszew.campaign.common.dedupe

import java.net.URI

/**
 * Comparison form of a listing URL (DEDUPE.md Step 2): lowercase
 * scheme://host/path without trailing slash; keep only ?jobid=<lowercased
 * value> when a jobid param exists (any case), otherwise drop the query.
 */
object UrlNormalizer {

    private val JOB_ID_PARAMS = setOf("jobid")

    fun normalize(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return url.trim()
        val scheme = uri.scheme?.lowercase() ?: return url.trim()
        val host = (uri.host ?: "").lowercase()
        if (host.isEmpty()) return url.trim()
        val defaultPort = when (scheme) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
        val port = if (uri.port > 0 && uri.port != defaultPort) ":${uri.port}" else ""
        val path = (uri.path ?: "").lowercase().trimEnd('/')

        val query = uri.rawQuery ?: ""
        val jobId = query.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0].lowercase() in JOB_ID_PARAMS }
            ?.get(1)

        val base = "$scheme://$host$port$path"
        return if (jobId != null) "$base?jobid=${jobId.lowercase()}" else base
    }
}
