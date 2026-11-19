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
    val isNational: Boolean
)

object DefaultSources {
    // Playlist remote: edit playlist.m3u in GitHub to update channels without rebuilding APK.
    const val REMOTE_PLAYLIST =
        "https://raw.githubusercontent.com/Ro0TN3T/NUSA-TV/main/playlist.m3u"
}
