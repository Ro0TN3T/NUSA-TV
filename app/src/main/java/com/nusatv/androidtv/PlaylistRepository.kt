package com.nusatv.androidtv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object PlaylistRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val NATIONAL_PRIORITY = listOf(
        "antv", "trans tv", "trans7", "rtv", "gtv", "rcti",
        "sctv", "Indosiar", "tvri", "tvri nasional", "tv one",
        "metro tv", "kompas tv", "inews", "moji", "mnctv",
        "net", "mdtv", "cnn indonesia", "cnbc indonesia", "btv", "sin po tv", "garuda tv", "nusantara tv"
    )

    /** Load the live remote playlist first; use the bundled M3U only when offline/server-down. */
    suspend fun loadAllChannels(context: Context): List<TvChannel> = withContext(Dispatchers.IO) {
        val remoteSource = PlaylistSource(
            url = DefaultSources.REMOTE_PLAYLIST,
            name = "NUSA TV • Server",
            fallbackCountry = "",
            fallbackGroup = ""
        )
        val preferences = context.applicationContext.getSharedPreferences("nusa_playlist_cache", Context.MODE_PRIVATE)
        val (body, playlistSourceName) = try {
            val request = Request.Builder()
                .url(DefaultSources.REMOTE_PLAYLIST)
                .header("User-Agent", "Mozilla/5.0 (Android; NusaTV/2.1)")
                .header("Accept", "application/x-mpegURL, audio/x-mpegurl, text/plain, */*")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                val remoteBody = response.body?.string()?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Playlist server kosong")
                remoteBody to "NUSA TV • Server"
            }
        } catch (remoteError: Exception) {
            val cachedText = preferences.getString("last_valid_m3u", null)?.takeIf { it.isNotBlank() }
            val bundledText = runCatching {
                context.assets.open("playlist.m3u").bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrNull()?.takeIf { it.isNotBlank() }
            when {
                cachedText != null -> cachedText to "NUSA TV • Cache"
                bundledText != null -> bundledText to "NUSA TV • Cadangan lokal"
                else -> throw IllegalStateException("Playlist online gagal dan cache/cadangan tidak tersedia", remoteError)
            }
        }

        fun normalizeAndSort(text: String, sourceName: String): List<TvChannel> =
            parseM3U(text, remoteSource.copy(name = sourceName))
                .groupBy { it.key }
                .map { (_, channels) ->
                    val first = channels.first()
                    first.copy(sources = channels.flatMap { it.sources }.distinctBy { it.url }.toMutableList())
                }
                .sortedWith(compareBy<TvChannel>(
                    { !it.isNational },
                    { val idx = NATIONAL_PRIORITY.indexOf(canonicalName(it.name)); if (idx >= 0) idx else 999 },
                    { it.name.lowercase() }
                ))

        var parsed = normalizeAndSort(body, playlistSourceName)
        if (parsed.isEmpty() && playlistSourceName == "NUSA TV • Server") {
            val cachedText = preferences.getString("last_valid_m3u", null)?.takeIf { it.isNotBlank() }
            if (cachedText != null && cachedText != body) parsed = normalizeAndSort(cachedText, "NUSA TV • Cache")
            if (parsed.isEmpty()) {
                val bundledText = runCatching {
                    context.assets.open("playlist.m3u").bufferedReader(Charsets.UTF_8).use { it.readText() }
                }.getOrNull().orEmpty()
                parsed = normalizeAndSort(bundledText, "NUSA TV • Cadangan lokal")
            }
        }
        if (parsed.isEmpty()) throw IllegalStateException("Playlist tidak berisi channel valid")
        if (playlistSourceName == "NUSA TV • Server" && body.length <= 2_000_000) {
            preferences.edit().putString("last_valid_m3u", body).apply()
        }
        parsed
    }

    private data class DomainHeaderProfile(val referer: String, val origin: String)

    /**
     * Allowlisted first-party web origins for providers whose own host is used
     * as the stream host. This is a header compatibility list, not an access
     * bypass: it cannot grant access to subscription-only or DRM-protected media.
     */
    private val DOMAIN_HEADER_ALLOWLIST = mapOf(
        "dens.tv" to DomainHeaderProfile("https://www.dens.tv/", "https://www.dens.tv"),
        "maxstream.tv" to DomainHeaderProfile("https://maxstream.tv/", "https://maxstream.tv"),
        "rctiplus.com" to DomainHeaderProfile("https://www.rctiplus.com/", "https://www.rctiplus.com"),
        "visionplus.id" to DomainHeaderProfile("https://www.visionplus.id/", "https://www.visionplus.id"),
        "xlz.livebytexscore.com" to DomainHeaderProfile("https://xlz.livebytexscore.com/", "https://xlz.livebytexscore.com")
    )

    private fun headerProfileFor(streamUrl: String): DomainHeaderProfile? {
        val host = runCatching { java.net.URI(streamUrl).host?.lowercase()?.trimEnd('.') }.getOrNull()
            ?: return null
        return DOMAIN_HEADER_ALLOWLIST.entries.firstOrNull { (domain, _) ->
            host == domain || host.endsWith(".$domain")
        }?.value
    }

    private fun fetchAndParse(source: PlaylistSource): List<TvChannel> {
        return try {
            val request = Request.Builder()
                .url(source.url)
                .header("User-Agent", "Mozilla/5.0 (Android TV; NusaTV/1.0)")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                parseM3U(body, source)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseM3U(content: String, source: PlaylistSource): List<TvChannel> {
        val out = mutableListOf<TvChannel>()
        val lines = content.replace("\uFEFF", "").lines()

        var currentName = ""
        var currentProgramTitle: String? = null
        var currentLogo = ""
        var currentTvgId: String? = null
        var currentCountry = source.fallbackCountry
        var currentGroup = source.fallbackGroup
        var currentReferer: String? = null
        var currentOrigin: String? = null
        var currentUserAgent: String? = null
        var drmType: String? = null
        var licenseUrl: String? = null
        var licenseHeaders = mutableMapOf<String, String>()
        var invalidDrmConfig = false
        var currentMimeType: String? = null

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val commaIdx = line.lastIndexOf(',')
                    val attrs = if (commaIdx >= 0) line.substring(0, commaIdx) else line
                    val rawTitle = if (commaIdx >= 0) line.substring(commaIdx + 1).trim() else "Channel"

                    currentName = cleanName(rawTitle)
                    currentProgramTitle = listOf("tvg-program", "tvg-current", "program-title", "current-program")
                        .asSequence().map { extractAttr(attrs, it) }.firstOrNull { it.isNotBlank() }
                    currentCountry = source.fallbackCountry
                    currentGroup = source.fallbackGroup
                    currentLogo = extractAttr(attrs, "tvg-logo")
                    currentTvgId = extractAttr(attrs, "tvg-id")
                    val c = extractAttr(attrs, "tvg-country")
                    if (c.isNotEmpty()) currentCountry = if (c.equals("ID", true)) "Indonesia" else c
                    val g = extractAttr(attrs, "group-title")
                    if (g.isNotEmpty()) currentGroup = g

                    currentReferer = null
                    currentOrigin = null
                    currentUserAgent = null
                    drmType = null
                    licenseUrl = null
                    licenseHeaders = mutableMapOf()
                    invalidDrmConfig = false
                    currentMimeType = null
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(":")
                    if (opt.startsWith("http-referrer=", true) || opt.startsWith("http-referer=", true)) {
                        currentReferer = cleanHeaderValue(opt.substringAfter("=").trim())
                    } else if (opt.startsWith("http-user-agent=", true)) {
                        currentUserAgent = cleanHeaderValue(opt.substringAfter("=").trim())
                    } else if (opt.startsWith("http-origin=", true)) {
                        currentOrigin = cleanHeaderValue(opt.substringAfter("=").trim())
                    } else if (opt.startsWith("mimetype=", true)) {
                        currentMimeType = normalizeMimeType(opt.substringAfter("=").trim())
                    }
                }
                line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                    val property = line.substringAfter(":").trim()
                    val key = property.substringBefore("=", "").trim().lowercase()
                    val value = property.substringAfter("=", "").trim()
                    when (key) {
                        "inputstream.adaptive.stream_headers" -> {
                            value.split('&').forEach { pair ->
                                val eq = pair.indexOf('=')
                                if (eq > 0) {
                                    val headerName = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8.name()).trim()
                                    val headerValue = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8.name()).trim()
                                    when (headerName.lowercase()) {
                                        "user-agent" -> currentUserAgent = cleanHeaderValue(headerValue)
                                        "referer", "referrer" -> currentReferer = cleanHeaderValue(headerValue)
                                        "origin" -> currentOrigin = cleanHeaderValue(headerValue)
                                    }
                                }
                            }
                        }
                        "inputstream.adaptive.manifest_type" -> {
                            currentMimeType = when (value.lowercase()) {
                                "hls", "m3u8" -> "application/x-mpegURL"
                                "mpd", "dash" -> "application/dash+xml"
                                else -> currentMimeType
                            }
                        }
                        "mimetype" -> currentMimeType = normalizeMimeType(value)
                        "inputstream.adaptive.license_type" -> {
                            drmType = when (value.lowercase()) {
                                "com.widevine.alpha", "widevine" -> "widevine"
                                "org.w3.clearkey", "clearkey" -> "clearkey"
                                else -> null
                            }
                            if (drmType == null) invalidDrmConfig = true
                        }
                        "inputstream.adaptive.license_key" -> {
                            // Kodi-style form: https://license.example/path|Header=Value&Header2=Value|flags
                            // Inline key material is intentionally not imported.
                            val parts = value.split('|')
                            val uriPart = parts.firstOrNull()?.trim().orEmpty()
                            val parsedUri = runCatching { java.net.URI(uriPart) }.getOrNull()
                            if (parsedUri != null && parsedUri.scheme.equals("https", true) && !parsedUri.host.isNullOrBlank()) {
                                licenseUrl = uriPart
                                if (parts.size > 1) {
                                    parts[1].split('&').forEach { pair ->
                                        val eq = pair.indexOf('=')
                                        if (eq > 0) {
                                            val headerName = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8.name()).trim()
                                            val headerValue = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8.name()).trim()
                                            if (headerName.isNotBlank() && headerValue.isNotBlank() && !headerName.contains('\n') && !headerValue.contains('\n')) {
                                                licenseHeaders[headerName] = headerValue
                                            }
                                        }
                                    }
                                }
                            } else {
                                invalidDrmConfig = true
                            }
                        }
                    }
                }
                line.startsWith("http://", true) || line.startsWith("https://", true) -> {
                    val drmReady = !invalidDrmConfig && ((drmType == null && licenseUrl == null) || (drmType != null && licenseUrl != null))
                    if (currentName.isNotEmpty() && drmReady && isValidStreamUrl(line)) {
                        val key = canonicalName(currentName)
                        val isIndo = currentCountry.equals("Indonesia", true) ||
                                currentGroup.contains("indonesia", true) ||
                                currentGroup.contains("nasional", true) ||
                                NATIONAL_PRIORITY.contains(key)

                        // Provider header profiles are applied only when the stream URL host
                        // itself belongs to a listed provider. CDN hosts must declare their
                        // provider headers explicitly in the M3U metadata; do not guess them.
                        val domainProfile = headerProfileFor(line)
                        // RCTI+ may deliver media from a CDN hostname. Match the channel
                        // label as well as the URL host so the supplied first-party headers
                        // are retained for that channel's stream requests.
                        val isRctiChannel = canonicalName(currentName).contains("rcti")
                        val rctiProfile = if (isRctiChannel) DomainHeaderProfile(
                            "https://www.rctiplus.com/", "https://www.rctiplus.com"
                        ) else null
                        val inferredReferer = currentReferer ?: domainProfile?.referer ?: rctiProfile?.referer
                        val inferredOrigin = currentOrigin ?: domainProfile?.origin ?: rctiProfile?.origin
                        val inferredUserAgent = currentUserAgent ?: if (isRctiChannel)
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"
                        else "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                        val needsHeader = inferredReferer != null || inferredOrigin != null || currentUserAgent != null || isRctiChannel

                        val candidate = StreamCandidate(
                            url = line,
                            sourceName = source.name,
                            userAgent = inferredUserAgent,
                            referer = inferredReferer,
                            origin = inferredOrigin,
                            requiresCustomHeader = needsHeader,
                            drmType = drmType,
                            licenseUrl = licenseUrl,
                            licenseHeaders = licenseHeaders.toMap(),
                            mimeType = currentMimeType ?: inferMimeType(line)
                        )

                        out.add(
                            TvChannel(
                                key = key,
                                name = currentName,
                                logo = currentLogo,
                                country = if (isIndo) "Indonesia" else currentCountry.ifEmpty { "Umum" },
                                categories = if (isIndo) listOf("Indonesia", currentGroup.ifEmpty { "Umum" }) else listOf(currentGroup.ifEmpty { "Umum" }),
                                sources = mutableListOf(candidate),
                                isNational = NATIONAL_PRIORITY.contains(key),
                                programTitle = currentProgramTitle,
                                tvgId = currentTvgId
                            )
                        )
                    }
                    currentName = ""
                }
            }
        }
        return out
    }

    /**
     * M3U header directives may quote values. Strip only matching outer quotes and
     * reject line breaks so malformed playlist text cannot inject extra headers.
     */
    private fun cleanHeaderValue(value: String): String? {
        val cleaned = value.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        return cleaned.takeIf { it.isNotEmpty() && '\r' !in it && '\n' !in it }
    }

    private fun extractAttr(attrs: String, key: String): String {
        val pattern = "(?:^|\\s)$key=\"([^\"]*)\""
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
        return regex.find(attrs)?.groupValues?.getOrNull(1)?.trim() ?: ""
    }

    private fun inferMimeType(url: String): String? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val path = uri.path.orEmpty().lowercase()
        return when {
            path.endsWith(".m3u8") -> "application/x-mpegURL"
            path.endsWith(".mpd") -> "application/dash+xml"
            // Samsung TV Plus entries in the supplied playlist use extensionless stvp-* URLs.
            // Treat as HLS manifests; actual availability and provider authorization still apply.
            uri.host.equals("jmp2.uk", true) && path.startsWith("/stvp-") -> "application/x-mpegURL"
            else -> null
        }
    }

    private fun normalizeMimeType(value: String): String? = when (value.lowercase()) {
        "application/x-mpegurl", "application/vnd.apple.mpegurl", "application/mpegurl" -> "application/x-mpegURL"
        "application/dash+xml", "application/mpd" -> "application/dash+xml"
        else -> null
    }

    private fun isValidStreamUrl(url: String): Boolean {
        // Jangan menolak URL hanya karena ekstensi tidak terlihat atau ada query string.
        // Beberapa endpoint resmi melakukan redirect atau menyajikan manifest dari URL dinamis.
        // Apakah respons benar-benar media akan diputuskan oleh Media3 saat playback.
        return try {
            val uri = java.net.URI(url)
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    fun cleanName(raw: String): String {
        return raw
            .replace(Regex("\\[[^\\]]*]"), "")
            .replace(Regex("\\([^)]*\\)"), "")
            .replace(Regex("\\s+(HD|FHD|UHD|SD|1080P|720P|480P|360P)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { "Channel" }
    }

    fun canonicalName(name: String): String {
        val normalized = Normalizer.normalize(cleanName(name).lowercase(), Normalizer.Form.NFD)
            .replace(Regex("[\\p{InCombiningDiacriticalMarks}]"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

        return when (normalized) {
            "mnc tv" -> "mnctv"
            "global tv", "global television" -> "gtv"
            "rajawali tv" -> "rtv"
            "tv one" -> "tvone"
            "trans tv" -> "trans tv"
            "trans 7" -> "trans7"
            "i news", "inews tv" -> "inews"
            "kompas television" -> "kompas tv"
            "tvri", "tvri nasional" -> "tvri nasional"
            "net", "net tv", "mdtv" -> "net"
            else -> normalized
        }
    }
}
