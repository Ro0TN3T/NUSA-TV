package com.nusatv.androidtv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    private fun getNationalFallbacks(): List<TvChannel> {
        val densReferer = "https://www.dens.tv/"
        val densOrigin = "https://www.dens.tv"
        val vidioReferer = "https://www.vidio.com/"

        return listOf(
            createFallback("RCTI", "https://upload.wikimedia.org/wikipedia/commons/thumb/d/dd/RCTI_logo_2015.svg/512px-RCTI_logo_2015.svg.png", listOf(
                StreamCandidate("https://op-flashcon-digdayahd-1.dens.tv/s/s01/index.m3u8", "DensTV • Native Header", referer = densReferer, origin = densOrigin, requiresCustomHeader = true)
            )),
            createFallback("SCTV", "https://upload.wikimedia.org/wikipedia/commons/thumb/c/cc/SCTV_Logo.svg/512px-SCTV_Logo.svg.png", listOf(
                StreamCandidate("https://op-flashcon-digdayahd-1.dens.tv/h/h217/01.m3u8?app_type=web&userid=lite&chname=SCTV", "DensTV • Native Header", referer = densReferer, origin = densOrigin, requiresCustomHeader = true),
                StreamCandidate("https://kmklive-lh.akamaihd.net/i/sctv_live@577566/master.m3u8", "Vidio/Akamai • Backup", referer = vidioReferer, requiresCustomHeader = true)
            )),
            createFallback("Indosiar", "https://upload.wikimedia.org/wikipedia/commons/thumb/3/33/Indosiar_2015.svg/512px-Indosiar_2015.svg.png", listOf(
                StreamCandidate("https://op-flashcon-digdayahd-1.dens.tv/h/h207/01.m3u8", "DensTV • Native Header", referer = densReferer, origin = densOrigin, requiresCustomHeader = true),
                StreamCandidate("https://kmklive-lh.akamaihd.net/i/indosiar_live@577566/master.m3u8", "Vidio/Akamai • Backup", referer = vidioReferer, requiresCustomHeader = true)
            )),
            createFallback("MNCTV", "https://upload.wikimedia.org/wikipedia/commons/thumb/8/87/MNCTV_logo.svg/512px-MNCTV_logo.svg.png", listOf(
                StreamCandidate("https://op-flashcon-digdayahd-1.dens.tv/s/s03/index.m3u8", "DensTV • Native Header", referer = densReferer, origin = densOrigin, requiresCustomHeader = true)
            )),
            createFallback("GTV", "https://upload.wikimedia.org/wikipedia/commons/thumb/9/96/GTV_2017.svg/512px-GTV_2017.svg.png", listOf(
                StreamCandidate("https://op-flashcon-digdayahd-1.dens.tv/s/s04/index.m3u8", "DensTV • Native Header", referer = densReferer, origin = densOrigin, requiresCustomHeader = true)
            )),
            createFallback("ANTV", "https://upload.wikimedia.org/wikipedia/commons/thumb/2/2e/ANTV_logo.svg/512px-ANTV_logo.svg.png", listOf(
                StreamCandidate("https://op-flashcon-digdayahd-1.dens.tv/h/h208/01.m3u8", "DensTV • Native Header", referer = densReferer, origin = densOrigin, requiresCustomHeader = true)
            )),
            createFallback("Trans TV", "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e1/Trans_TV_2013.svg/512px-Trans_TV_2013.svg.png", listOf(
                StreamCandidate("https://video.detik.com/transtv/smil:transtv.smil/playlist.m3u8", "Detik/Transmedia • Resmi"),
                StreamCandidate("https://op-flashcon-digdayahd-1.dens.tv/h/h215/01.m3u8", "DensTV • Backup", referer = densReferer, origin = densOrigin, requiresCustomHeader = true)
            )),
            createFallback("Trans7", "https://upload.wikimedia.org/wikipedia/commons/thumb/6/6b/Trans7_2013.svg/512px-Trans7_2013.svg.png", listOf(
                StreamCandidate("https://video.detik.com/trans7/smil:trans7.smil/playlist.m3u8", "Detik/Transmedia • Resmi"),
                StreamCandidate("https://op-flashcon-digdayahd-1.dens.tv/h/h216/01.m3u8", "DensTV • Backup", referer = densReferer, origin = densOrigin, requiresCustomHeader = true)
            )),
            createFallback("TVRI Nasional", "https://upload.wikimedia.org/wikipedia/commons/thumb/5/51/TVRI_logo_2019.svg/512px-TVRI_logo_2019.svg.png", listOf(
                StreamCandidate("https://ott-balancer.tvri.go.id/live/eds/Nasional/hls/Nasional.m3u8", "TVRI OTT • Resmi")
            )),
            createFallback("tvOne", "https://upload.wikimedia.org/wikipedia/commons/thumb/9/91/TvOne_2023.svg/512px-TvOne_2023.svg.png", listOf(
                StreamCandidate("https://op-flashcon-digdayahd-1.dens.tv/h/h209/01.m3u8", "DensTV • Native Header", referer = densReferer, origin = densOrigin, requiresCustomHeader = true)
            )),
            createFallback("Metro TV", "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a2/Metro_TV_logo.svg/512px-Metro_TV_logo.svg.png", listOf(
                StreamCandidate("https://edge.medcom.id/live-edge/smil:metrotv.smil/playlist.m3u8", "Medcom • Resmi"),
                StreamCandidate("https://op-flashcon-digdayahd-1.dens.tv/h/h210/01.m3u8", "DensTV • Backup", referer = densReferer, origin = densOrigin, requiresCustomHeader = true)
            )),
            createFallback("Kompas TV", "https://upload.wikimedia.org/wikipedia/commons/thumb/4/45/Kompas_TV_2017.svg/512px-Kompas_TV_2017.svg.png", listOf(
                StreamCandidate("https://op-flashcon-digdayahd-1.dens.tv/h/h211/01.m3u8", "DensTV • Native Header", referer = densReferer, origin = densOrigin, requiresCustomHeader = true)
            )),
            createFallback("CNN Indonesia", "https://upload.wikimedia.org/wikipedia/commons/thumb/6/64/CNN_Indonesia_logo.svg/512px-CNN_Indonesia_logo.svg.png", listOf(
                StreamCandidate("https://live.cnnindonesia.com/livecnn/smil:cnntv.smil/playlist.m3u8", "CNN Indonesia • Resmi")
            )),
            createFallback("CNBC Indonesia", "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e3/CNBC_Indonesia_logo.svg/512px-CNBC_Indonesia_logo.svg.png", listOf(
                StreamCandidate("https://live.cnbcindonesia.com/livecnbc/smil:cnbctv.smil/playlist.m3u8", "CNBC Indonesia • Resmi")
            )),
            createFallback("RTV", "https://upload.wikimedia.org/wikipedia/commons/thumb/8/87/RTV_logo.svg/512px-RTV_logo.svg.png", listOf(
                StreamCandidate("https://op-flashcon-digdayahd-1.dens.tv/h/h213/01.m3u8", "DensTV • Native Header", referer = densReferer, origin = densOrigin, requiresCustomHeader = true)
            )),
            createFallback("Moji", "https://upload.wikimedia.org/wikipedia/commons/thumb/3/3a/Moji_logo.svg/512px-Moji_logo.svg.png", listOf(
                StreamCandidate("https://op-flashcon-digdayahd-1.dens.tv/h/h212/01.m3u8", "DensTV • Native Header", referer = densReferer, origin = densOrigin, requiresCustomHeader = true)
            )),
            createFallback("iNews", "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a7/INews_logo.svg/512px-INews_logo.svg.png", listOf(
                StreamCandidate("https://op-flashcon-digdayahd-1.dens.tv/s/s02/index.m3u8", "DensTV • Native Header", referer = densReferer, origin = densOrigin, requiresCustomHeader = true)
            ))
        )
    }

    private fun createFallback(name: String, logo: String, sources: List<StreamCandidate>): TvChannel {
        val key = canonicalName(name)
        return TvChannel(
            key = key,
            name = name,
            logo = logo,
            country = "Indonesia",
            categories = listOf("Indonesia", "TV Nasional"),
            sources = sources.toMutableList(),
            isNational = true
        )
    }

    suspend fun loadAllChannels(): List<TvChannel> = withContext(Dispatchers.IO) {
        val channelMap = LinkedHashMap<String, TvChannel>()

        for (fb in getNationalFallbacks()) {
            channelMap[fb.key] = fb
        }

        val deferredResults = DefaultSources.PLAYLISTS.map { src ->
            async {
                fetchAndParse(src)
            }
        }
        val parsedLists = deferredResults.awaitAll()

        for (items in parsedLists) {
            for (item in items) {
                val existing = channelMap[item.key]
                if (existing == null) {
                    channelMap[item.key] = item
                } else {
                    for (cand in item.sources) {
                        if (existing.sources.none { it.url == cand.url }) {
                            existing.sources.add(cand)
                        }
                    }
                }
            }
        }

        channelMap.values.sortedWith(compareBy<TvChannel>(
            { !it.isNational },
            {
                val idx = NATIONAL_PRIORITY.indexOf(canonicalName(it.name))
                if (idx >= 0) idx else 999
            },
            { it.country != "Indonesia" },
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
                    currentLogo = extractAttr(attrs, "tvg-logo")
                    val c = extractAttr(attrs, "tvg-country")
                    if (c.isNotEmpty()) currentCountry = if (c.equals("ID", true)) "Indonesia" else c
                    val g = extractAttr(attrs, "group-title")
                    if (g.isNotEmpty()) currentGroup = g

                    currentReferer = null
                    currentUserAgent = null
                    hasDrm = false
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(":")
                    if (opt.startsWith("http-referrer=", true)) {
                        currentReferer = opt.substringAfter("=").trim()
                    } else if (opt.startsWith("http-user-agent=", true)) {
                        currentUserAgent = opt.substringAfter("=").trim()
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
                                NATIONAL_PRIORITY.contains(key)

                        val inferredReferer = currentReferer ?: if (line.contains("dens.tv", true)) "https://www.dens.tv/" else null
                        val inferredOrigin = if (line.contains("dens.tv", true)) "https://www.dens.tv" else null
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
        val lower = url.lowercase()
        if (lower.endsWith(".mpd") || lower.endsWith(".mp4") || lower.endsWith(".mkv")) return false
        return lower.contains(".m3u8") || !lower.substringAfterLast('/').contains('.')
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
