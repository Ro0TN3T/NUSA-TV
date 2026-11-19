package com.nusatv.androidtv

data class PlaylistSource(
    val url: String,
    val name: String,
    val fallbackCountry: String = "",
    val fallbackGroup: String = ""
)

data class StreamCandidate(
    val url: String,
    val sourceName: String,
    val userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    val referer: String? = null,
    val origin: String? = null,
    val requiresCustomHeader: Boolean = false,
    val drmType: String? = null,
    val licenseUrl: String? = null,
    val licenseHeaders: Map<String, String> = emptyMap(),
    val mimeType: String? = null
)

data class TvChannel(
    val key: String,
    val name: String,
    val logo: String,
    val country: String,
    val categories: List<String>,
    val sources: MutableList<StreamCandidate>,
    val isNational: Boolean,
    val programTitle: String? = null,
    val tvgId: String? = null,
    val currentProgramTitle: String? = null,
    val nextProgramTitle: String? = null
)

object DefaultSources {
    // Remote playlist served by the NUSA TV Cloudflare Worker.
    const val XMLTV_URL = "https://raw.githubusercontent.com/dhasap/dhanytv/refs/heads/main/epg.xml"
    const val REMOTE_PLAYLIST =
        "https://spring-grass-97c6.nusatv.workers.dev/api/playlist.m3u"
}
