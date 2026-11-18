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
    val requiresCustomHeader: Boolean = false
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
    val PLAYLISTS = listOf(
        PlaylistSource(
            url = "https://iptv-org.github.io/iptv/countries/id.m3u",
            name = "IPTV-ORG • Indonesia",
            fallbackCountry = "Indonesia",
            fallbackGroup = "Indonesia"
        ),
        PlaylistSource(
            url = "https://raw.githubusercontent.com/dhasap/dhanytv/main/dhanytv.m3u",
            name = "DhanyTV • Utama",
            fallbackCountry = "Indonesia",
            fallbackGroup = "Indonesia"
        ),
        PlaylistSource(
            url = "https://raw.githubusercontent.com/dhasap/dhanytv/main/dhanytv-ott.m3u",
            name = "DhanyTV • OTT/HLS",
            fallbackCountry = "Indonesia",
            fallbackGroup = "Indonesia"
        ),
        PlaylistSource(
            url = "https://raw.githubusercontent.com/mikahoy045/iptv-id/main/tv_indonesia.m3u",
            name = "IPTV Indonesia • 221 Channel",
            fallbackCountry = "Indonesia",
            fallbackGroup = "Indonesia"
        ),
        PlaylistSource(
            url = "https://raw.githubusercontent.com/Sprtcrnbry/streaming-tv-indonesia/master/playlist.m3u",
            name = "Streaming TV Indonesia",
            fallbackCountry = "Indonesia",
            fallbackGroup = "Indonesia"
        ),
        PlaylistSource(
            url = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlists/playlist_indonesia.m3u8",
            name = "Free-TV • Indonesia",
            fallbackCountry = "Indonesia",
            fallbackGroup = "Indonesia"
        )
    )
}
