package com.nusatv.androidtv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.concurrent.TimeUnit

object PlaylistRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val NATIONAL_PRIORITY = listOf(
        "rcti", "sctv", "indosiar", "mnctv", "gtv", "antv",
        "trans tv", "trans7", "tvri", "tvri nasional", "tv one",
        "metro tv", "kompas tv", "inews", "moji", "rtv",
        "net", "mdtv", "cnn indonesia", "cnbc indonesia", "btv", "sin po tv", "garuda tv", "nusantara tv"
    )

    suspend fun loadAllChannels(): List<TvChannel> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(DefaultSources.REMOTE_PLAYLIST)
            .header("User-Agent", "Mozilla/5.0 (Android; NusaTV/1.0)")
            .build()

        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Playlist GitHub gagal dimuat: HTTP ${response.code}")
            }
            response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Playlist GitHub kosong")
        }

        val source = PlaylistSource(
            url = DefaultSources.REMOTE_PLAYLIST,
            name = "NUSA TV • GitHub",
            fallbackCountry = "",
            fallbackGroup = ""
        )

        parseM3U(body, source)
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
        var currentLogo = ""
        var currentCountry = source.fallbackCountry
        var currentGroup = source.fallbackGroup
        var currentReferer: String? = null
        var currentOrigin: String? = null
        var currentUserAgent: String? = null
        var hasDrm = false

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val commaIdx = line.lastIndexOf(',')
                    val attrs = if (commaIdx >= 0) line.substring(0, commaIdx) else line
                    val rawTitle = if (commaIdx >= 0) line.substring(commaIdx + 1).trim() else "Channel"

                    currentName = cleanName(rawTitle)
                    currentCountry = source.fallbackCountry
                    currentGroup = source.fallbackGroup
                    currentLogo = extractAttr(attrs, "tvg-logo")
                    val c = extractAttr(attrs, "tvg-country")
                    if (c.isNotEmpty()) currentCountry = if (c.equals("ID", true)) "Indonesia" else c
                    val g = extractAttr(attrs, "group-title")
                    if (g.isNotEmpty()) currentGroup = g

                    currentReferer = null
                    currentOrigin = null
                    currentUserAgent = null
                    hasDrm = false
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(":")
                    if (opt.startsWith("http-referrer=", true)) {
                        currentReferer = opt.substringAfter("=").trim()
                    } else if (opt.startsWith("http-user-agent=", true)) {
                        currentUserAgent = opt.substringAfter("=").trim()
                    } else if (opt.startsWith("http-origin=", true)) {
                        // Origin diturunkan dari referer jika provider mengatur keduanya
                        // lewat konfigurasi playlist; saat ini StreamCandidate menyimpan origin.
                        currentOrigin = opt.substringAfter("=").trim()
                    }
                }
                line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                    if (line.contains("widevine", true) || line.contains("clearkey", true) || line.contains("license_key", true)) {
                        hasDrm = true
                    }
                }
                line.startsWith("http://", true) || line.startsWith("https://", true) -> {
                    if (currentName.isNotEmpty() && !hasDrm && isValidStreamUrl(line)) {
                        val key = canonicalName(currentName)
                        val isIndo = currentCountry.equals("Indonesia", true) ||
                                currentGroup.contains("indonesia", true) ||
                                currentGroup.contains("nasional", true) ||
                                NATIONAL_PRIORITY.contains(key)

                        val inferredReferer = currentReferer ?: if (line.contains("dens.tv", true)) "https://www.dens.tv/" else null
                        val inferredOrigin = currentOrigin ?: if (line.contains("dens.tv", true)) "https://www.dens.tv" else null
                        val needsHeader = inferredReferer != null || currentUserAgent != null

                        val candidate = StreamCandidate(
                            url = line,
                            sourceName = source.name,
                            userAgent = currentUserAgent ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                            referer = inferredReferer,
                            origin = inferredOrigin,
                            requiresCustomHeader = needsHeader
                        )

                        out.add(
                            TvChannel(
                                key = key,
                                name = currentName,
                                logo = currentLogo,
                                country = if (isIndo) "Indonesia" else currentCountry.ifEmpty { "Umum" },
                                categories = if (isIndo) listOf("Indonesia", currentGroup.ifEmpty { "Umum" }) else listOf(currentGroup.ifEmpty { "Umum" }),
                                sources = mutableListOf(candidate),
                                isNational = NATIONAL_PRIORITY.contains(key)
                            )
                        )
                    }
                    currentName = ""
                }
            }
        }
        return out
    }

    private fun extractAttr(attrs: String, key: String): String {
        val pattern = "(?:^|\\s)$key=\"([^\"]*)\""
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
        return regex.find(attrs)?.groupValues?.getOrNull(1)?.trim() ?: ""
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
