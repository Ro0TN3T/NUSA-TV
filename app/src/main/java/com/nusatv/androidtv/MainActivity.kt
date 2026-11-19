package com.nusatv.androidtv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.Gravity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import android.view.WindowManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.GridLayoutManager
import com.nusatv.androidtv.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.IOException
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ChannelAdapter

    private var player: ExoPlayer? = null
    private var allChannels: List<TvChannel> = emptyList()
    private var currentChannel: TvChannel? = null
    private var currentSourceIndex = 0
    private var selectedCategory = "Indonesia"
    private var categoryDialog: AlertDialog? = null
    private var isFullscreen = false
    private var isAndroidTv = false
    private var wasPlayingBeforeStop = false
    private var transientRetryCount = 0
    private var isTryingFallback = false
    private var playbackGeneration = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bufferingTimeout = Runnable {
        if (player?.playbackState == Player.STATE_BUFFERING) {
            tryNextSource("Buffering timeout setelah 15 detik")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isAndroidTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        requestedOrientation = if (isAndroidTv) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupControls()

        allChannels = ChannelCache.channels
        if (allChannels.isEmpty()) {
            reloadPlaylists()
        } else {
            applyFilter()
            val firstChannel = allChannels.firstOrNull { it.country == "Indonesia" } ?: allChannels.firstOrNull()
            firstChannel?.let { playChannel(it, enterFull = false) }
        }
    }

    private fun setupRecyclerView() {
        adapter = ChannelAdapter { channel ->
            // Selecting a channel changes the stream in the embedded player only.
            playChannel(channel, enterFull = false)
        }
        binding.rvChannels.layoutManager = GridLayoutManager(this, if (isAndroidTv) 3 else 1)
        binding.rvChannels.adapter = adapter
    }

    private fun setupControls() {
        binding.btnFilterIndo.visibility = View.GONE
        binding.btnFullscreen.visibility = View.GONE
        binding.etSearch.visibility = View.GONE
        binding.btnFullscreen.setOnClickListener { requestLandscapeFullscreen() }

        binding.playerContainer.setOnClickListener {
            toggleFullscreen(!isFullscreen)
        }

        binding.btnReload.setOnClickListener {
            reloadPlaylists()
        }

        binding.btnStop.text = "⛶"
        binding.btnStop.contentDescription = "Perbesar layar"
        binding.btnStop.setOnClickListener { requestLandscapeFullscreen() }

        binding.btnRetry.setOnClickListener {
            currentChannel?.let { ch ->
                if (ch.sources.size > 1) {
                    currentSourceIndex = (currentSourceIndex + 1) % ch.sources.size
                }
                transientRetryCount = 0
                isTryingFallback = false
                startStream(ch, currentSourceIndex)
            }
        }


    }

    private fun reloadPlaylists() {
        binding.btnReload.isEnabled = false
        binding.tvStatus.text = "Memperbarui playlist TV Nasional & global..."
        lifecycleScope.launch {
            try {
                allChannels = PlaylistRepository.loadAllChannels(this@MainActivity)
                ChannelCache.channels = allChannels
                applyFilter()
                binding.tvStatus.text = "${allChannels.size} channel dimuat dari server NUSA TV."
                if (currentChannel == null && allChannels.isNotEmpty()) {
                    val first = allChannels.firstOrNull { it.country == "Indonesia" } ?: allChannels.first()
                    playChannel(first, enterFull = false)
                }
            } catch (error: Exception) {
                binding.tvStatus.text = "Gagal memuat playlist server. Periksa koneksi internet."
                if (allChannels.isEmpty()) adapter.submitList(emptyList())
            } finally {
                binding.btnReload.isEnabled = true
            }
        }
    }

    private fun applyFilter() {
        adapter.submitList(allChannels, currentChannel?.key)
        binding.tvStats.text = "${allChannels.size} channel"
        binding.tvSectionTitle.text = "Daftar Channel"
        updateServerTabs()
        if (isAndroidTv && !isFullscreen && allChannels.isNotEmpty()) {
            binding.rvChannels.post {
                if (!binding.rvChannels.hasFocus()) {
                    binding.rvChannels.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
            }
        }
    }

    private fun updateServerTabs() {
        val host = binding.serverTabs ?: return
        host.removeAllViews()
        val sources = currentChannel?.sources.orEmpty()
        if (sources.isEmpty()) {
            host.visibility = View.GONE
            return
        }
        host.visibility = View.VISIBLE
        sources.forEachIndexed { index, source ->
            val chip = TextView(this).apply {
                text = "Server ${index + 1}"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(13), dp(8), dp(13), dp(8))
                background = if (index == currentSourceIndex) rounded("#1762D7", "#16D7EA", 24)
                    else rounded("#171A28", "#30364D", 24)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(-2, dp(42)).apply {
                    marginEnd = dp(7)
                }
                contentDescription = "Putar ${source.sourceName}"
                setOnClickListener {
                    currentSourceIndex = index
                    transientRetryCount = 0
                    isTryingFallback = false
                    currentChannel?.let { startStream(it, index) }
                }
            }
            host.addView(chip)
        }
    }

    private data class CategoryOption(val title: String, val code: String, val badge: String = "")

    private fun showCategoryDialog() {
        val options = listOf(
            CategoryOption("Semua Channel", "ALL"),
            CategoryOption("Events", "EV", "LIVE"),
            CategoryOption("Indonesia", "IN"),
            CategoryOption("Sports", "SP"),
            CategoryOption("Kids • TV Anak", "KI"),
            CategoryOption("Blockbuster", "BL"),
            CategoryOption("Knowledge", "KN"),
            CategoryOption("News • Berita TV", "NE")
        )

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded("#151824", "#30364D", 22)
        }
        val title = TextView(this).apply {
            text = "Pilih Kategori"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(8), dp(8), dp(14))
        }
        panel.addView(title)

        options.forEach { option ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(50)
                setPadding(dp(8), 0, dp(8), 0)
                background = if (selectedCategory == option.title.substringBefore(" •") ||
                    (option.code == "ALL" && selectedCategory == "Semua")) rounded("#19243A", "#16D7EA", 18)
                    else rounded("#171A28", "#292E43", 18)
                isClickable = true
                isFocusable = true
            }
            val label = TextView(this).apply {
                text = option.title
                setTextColor(Color.WHITE)
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f)
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(label)
            if (option.badge.isNotEmpty()) {
                val badge = TextView(this).apply {
                    text = option.badge
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(5), dp(12), dp(5))
                    background = rounded("#F32135", "#F32135", 18)
                }
                row.addView(badge)
            } else {
                val code = TextView(this).apply {
                    text = option.code
                    setTextColor(Color.rgb(145, 164, 188))
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    minWidth = dp(36)
                }
                row.addView(code)
            }
            row.setOnClickListener {
                selectedCategory = when (option.code) {
                    "ALL" -> "Semua"
                    "EV" -> "Events"
                    "IN" -> "Indonesia"
                    "SP" -> "Sports"
                    "KI" -> "Kids"
                    "BL" -> "Blockbuster"
                    "KN" -> "Knowledge"
                    else -> "News"
                }
                categoryDialog?.dismiss()
                applyFilter()
            }
            panel.addView(row)
        }

        val cancel = TextView(this).apply {
            text = "Batal"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(12), dp(26), dp(12))
            background = rounded("#202437", "#30364D", 28)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)
            ).apply {
                gravity = Gravity.END
                topMargin = dp(14)
                rightMargin = dp(4)
            }
            setOnClickListener { categoryDialog?.dismiss() }
        }
        panel.addView(cancel)

        categoryDialog = AlertDialog.Builder(this)
            .setView(panel)
            .create()
        categoryDialog?.setOnShowListener {
            categoryDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
            categoryDialog?.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.88f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            categoryDialog?.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            categoryDialog?.window?.let { dialogWindow ->
                val params = dialogWindow.attributes
                params.dimAmount = 0.68f
                dialogWindow.attributes = params
            }
        }
        categoryDialog?.show()
    }

    private fun channelMatchesCategory(channel: TvChannel, category: String): Boolean {
        val name = channel.name.lowercase()
        val groups = channel.categories.joinToString(" ").lowercase()
        val text = "$name $groups"
        return when (category) {
            "Indonesia" -> channel.country.equals("Indonesia", true) ||
                text.contains("indonesia") || text.contains("nasional")
            "Events" -> text.contains("event") || text.contains("live event")
            "Sports" -> listOf("sport", "sports", "olahraga", "football", "soccer", "bola").any { text.contains(it) }
            "Kids" -> listOf("kids", "kid", "anak", "children", "cartoon", "animation", "kartun").any { text.contains(it) }
            "Blockbuster" -> listOf("movie", "movies", "film", "cinema", "blockbuster").any { text.contains(it) }
            "Knowledge" -> listOf("knowledge", "education", "documentary", "edukasi", "science", "sains").any { text.contains(it) }
            "News" -> listOf("news", "berita", "cnbc", "cnn", "inews", "metro tv", "tv one").any { text.contains(it) }
            else -> false
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(fill: String, stroke: String, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(fill))
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), Color.parseColor(stroke))
        }

    private fun playChannel(channel: TvChannel, enterFull: Boolean) {
        if (channel.sources.isEmpty()) return
        currentChannel = channel
        transientRetryCount = 0
        isTryingFallback = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        currentSourceIndex = 0
        adapter.setSelectedChannel(channel.key)
        updateServerTabs()

        if (enterFull) requestLandscapeFullscreen()
        startStream(channel, 0)
    }

    @OptIn(UnstableApi::class)
    private fun startStream(channel: TvChannel, sourceIdx: Int) {
        val candidate = channel.sources.getOrNull(sourceIdx) ?: return
        mainHandler.removeCallbacks(bufferingTimeout)

        updateServerTabs()
        binding.tvLiveLabel.text = "● LIVE • SEDANG MEMUAT"
        binding.tvWelcomeTitle.text = channel.name
        binding.tvWelcomeDesc.text = channel.programTitle?.takeIf { it.isNotBlank() }
            ?: "Sumber: ${candidate.sourceName} • Info acara belum tersedia di playlist"
        binding.tvStatus.text = "Menghubungkan ke ${channel.name} (${sourceIdx + 1}/${channel.sources.size})..."

        releasePlayer()
        playbackGeneration += 1
        val activeGeneration = playbackGeneration

        // These properties are attached to the OkHttp-backed Media3 DataSource.
        // HLS uses the same factory for the master/media playlists and media segments.
        val headersMap = linkedMapOf<String, String>()
        candidate.userAgent.takeIf { it.isNotBlank() }?.let { headersMap["User-Agent"] = it }
        candidate.referer?.takeIf { it.isNotBlank() }?.let { headersMap["Referer"] = it }
        candidate.origin?.takeIf { it.isNotBlank() }?.let { headersMap["Origin"] = it }

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setDefaultRequestProperties(headersMap)

        // The factory is shared by HLS manifest, variant playlist, key (when permitted),
        // and media-segment requests. OkHttp follows HTTP and HTTPS redirects.
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val mediaItemBuilder = MediaItem.Builder().setUri(candidate.url)
        when (candidate.mimeType) {
            "application/x-mpegURL" -> mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            "application/dash+xml" -> mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        val drmUuid = when (candidate.drmType) {
            "widevine" -> C.WIDEVINE_UUID
            "clearkey" -> C.CLEARKEY_UUID
            else -> null
        }
        if (drmUuid != null && !candidate.licenseUrl.isNullOrBlank()) {
            val drmBuilder = MediaItem.DrmConfiguration.Builder(drmUuid)
                .setLicenseUri(candidate.licenseUrl!!)
                .setLicenseRequestHeaders(candidate.licenseHeaders)
            mediaItemBuilder.setDrmConfiguration(drmBuilder.build())
        }
        val mediaItem = mediaItemBuilder.build()
        var lastLoadFailure: String? = null

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                setMediaItem(mediaItem)
                playWhenReady = true
                volume = 1f

                // Record the exact class of failed request: HLS manifest vs media segment.
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onLoadError(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData,
                        error: IOException,
                        wasCanceled: Boolean
                    ) {
                        val requestType = when (mediaLoadData.dataType) {
                            C.DATA_TYPE_MANIFEST -> "playlist/manifest"
                            C.DATA_TYPE_MEDIA -> "segmen video"
                            C.DATA_TYPE_DRM -> "permintaan DRM"
                            else -> "request media (tipe ${mediaLoadData.dataType})"
                        }
                        val requestUri = loadEventInfo.dataSpec.uri
                        val requestHost = requestUri.host ?: "host tidak diketahui"
                        val httpFailure = findHttpFailure(error)
                        val failureText = buildString {
                            append(requestType)
                            if (httpFailure != null) {
                                append(" HTTP ${httpFailure.first}")
                                if (!httpFailure.second.isNullOrBlank()) append(": ${httpFailure.second}")
                            } else {
                                append(": ${error.message ?: error.javaClass.simpleName}")
                            }
                            append(" [")
                            append(requestHost)
                            append("]")
                        }
                        if (!wasCanceled) {
                            lastLoadFailure = failureText
                        }
                        Log.e(
                            "NUSA-TV-LOAD",
                            "Load failed: channel=${channel.name}, source=${candidate.sourceName}, type=$requestType, host=$requestHost, canceled=$wasCanceled",
                            error
                        )
                    }
                })

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (activeGeneration != playbackGeneration || currentChannel?.key != channel.key) return
                        when (playbackState) {
                            Player.STATE_READY -> {
                                mainHandler.removeCallbacks(bufferingTimeout)
                                transientRetryCount = 0
                                isTryingFallback = false
                                binding.tvLiveLabel.text = "● LIVE • SEDANG DIPUTAR"
                                binding.tvStatus.text = "LIVE • ${candidate.sourceName}"
                            }
                            Player.STATE_BUFFERING -> {
                                binding.tvStatus.text = "Buffering ${channel.name}..."
                                mainHandler.removeCallbacks(bufferingTimeout)
                                mainHandler.postDelayed(bufferingTimeout, 15_000L)
                            }
                            Player.STATE_ENDED -> {
                                tryNextSource("Siaran berakhir")
                            }
                            else -> Unit
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (activeGeneration != playbackGeneration || currentChannel?.key != channel.key) return
                        val detail = formatPlaybackError(error, lastLoadFailure)
                        Log.e(
                            "NUSA-TV-PLAYER",
                            "Playback failed: channel=${channel.name}, source=${candidate.sourceName}, " +
                                "host=${runCatching { java.net.URI(candidate.url).host }.getOrNull()}, detail=$detail",
                            error
                        )
                        tryNextSource(detail)
                    }
                })
                prepare()
            }

        player = exoPlayer
        binding.playerView.player = exoPlayer
    }

    /** Find an HTTP response code anywhere in the wrapped Media3/OkHttp exception chain. */
    private fun findHttpFailure(error: Throwable): Pair<Int, String?>? {
        var current: Throwable? = error
        val visited = HashSet<Throwable>()
        while (current != null && visited.add(current)) {
            if (current is HttpDataSource.InvalidResponseCodeException) {
                return current.responseCode to current.message
            }
            current = current.cause
        }
        return null
    }

    /** Convert Media3/HTTP failures into useful diagnostics without exposing URL query tokens. */
    private fun formatPlaybackError(error: PlaybackException, lastLoadFailure: String?): String {
        var current: Throwable? = error
        var deepestMessage: String? = null
        val visited = HashSet<Throwable>()

        while (current != null && visited.add(current)) {
            if (!current.message.isNullOrBlank()) deepestMessage = current.message
            current = current.cause
        }

        val httpFailure = findHttpFailure(error)
        val detail = when {
            httpFailure?.first == 401 -> "HTTP 401: server meminta autentikasi atau token yang masih berlaku"
            httpFailure?.first == 403 -> "HTTP 403: server menolak permintaan. Periksa izin sumber, masa berlaku link, dan header resmi dari penyedia"
            httpFailure?.first == 404 -> "HTTP 404: alamat stream atau manifest tidak ditemukan"
            httpFailure != null && httpFailure.first in 500..599 -> "HTTP ${httpFailure.first}: server sumber sedang bermasalah"
            !lastLoadFailure.isNullOrBlank() -> lastLoadFailure
            httpFailure != null -> "HTTP ${httpFailure.first}${httpFailure.second?.let { ": $it" } ?: ""}"
            deepestMessage?.contains("UnknownHost", true) == true -> "DNS / domain tidak ditemukan"
            deepestMessage?.contains("timeout", true) == true -> "Koneksi timeout"
            deepestMessage?.contains("Cleartext", true) == true -> "HTTP tidak aman diblokir Android"
            deepestMessage?.contains("SSL", true) == true || deepestMessage?.contains("certificate", true) == true -> "Koneksi TLS/sertifikat gagal"
            else -> deepestMessage?.take(180) ?: error.errorCodeName
        }
        return "${error.errorCodeName}: $detail"
    }

    private fun tryNextSource(reason: String) {
        if (isTryingFallback) return
        isTryingFallback = true
        val ch = currentChannel ?: run { isTryingFallback = false; return }
        if (currentSourceIndex + 1 < ch.sources.size) {
            currentSourceIndex++
            transientRetryCount = 0
            binding.tvStatus.text = "Sumber gagal. Beralih ke link ${currentSourceIndex + 1}/${ch.sources.size}..."
            isTryingFallback = false
            startStream(ch, currentSourceIndex)
        } else if (transientRetryCount < 1 && isTransientFailure(reason)) {
            transientRetryCount++
            binding.tvStatus.text = "Koneksi terputus. Mencoba sambung ulang..."
            lifecycleScope.launch {
                kotlinx.coroutines.delay(1200)
                isTryingFallback = false
                if (currentChannel?.key == ch.key) startStream(ch, currentSourceIndex)
            }
        } else {
            isTryingFallback = false
            binding.tvLiveLabel.text = "● GAGAL DIPUTAR"
            binding.tvStatus.text = "Gagal memutar ${ch.name}. $reason. Coba link lain atau periksa akses sumber."
            if (isFullscreen) toggleFullscreen(false)
        }
    }

    private fun isTransientFailure(reason: String): Boolean =
        reason.contains("timeout", true) || reason.contains("HTTP 5", true) ||
            reason.contains("UnknownHost", true) || reason.contains("DNS", true) ||
            reason.contains("connection", true) || reason.contains("I/O", true)

    private fun stopPlayback() {
        releasePlayer()
        currentChannel = null
        if (isFullscreen) {
            setFullscreenUi(false)
            if (!isAndroidTv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        adapter.setSelectedChannel(null)
        updateServerTabs()
        binding.tvLiveLabel.text = "● NUSA TV • SIAP MENONTON"
        binding.tvWelcomeTitle.text = "Pilih channel"
        binding.tvWelcomeDesc.text = "Pilih channel untuk mulai menonton."
        binding.tvStatus.text = "Pemutaran dihentikan."
    }

    private fun requestLandscapeFullscreen() {
        if (isAndroidTv) {
            setFullscreenUi(true)
            return
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setFullscreenUi(true)
    }

    private fun setFullscreenUi(enable: Boolean) {
        if (isAndroidTv && !enable) return
        isFullscreen = enable
        if (enable) {
            binding.headerBar.visibility = View.GONE
            binding.welcomePanel.visibility = View.GONE
            binding.statsBar.visibility = View.GONE
            binding.rvChannels.visibility = View.GONE
            binding.playerContainer.layoutParams = binding.playerContainer.layoutParams.apply {
                height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            }
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        } else {
            binding.headerBar.visibility = View.VISIBLE
            binding.welcomePanel.visibility = View.VISIBLE
            binding.statsBar.visibility = View.VISIBLE
            binding.rvChannels.visibility = View.VISIBLE
            val density = resources.displayMetrics.density
            binding.playerContainer.layoutParams = binding.playerContainer.layoutParams.apply {
                height = (210 * density).toInt()
            }
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun toggleFullscreen(enable: Boolean) {
        if (enable) requestLandscapeFullscreen() else {
            if (!isAndroidTv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            setFullscreenUi(false)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isAndroidTv) return
        val landscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        setFullscreenUi(landscape)
        // Once landscape has been reached, release the temporary lock so the
        // user can rotate back to portrait and automatically see the channel list.
        if (landscape && requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun changeChannel(step: Int): Boolean {
        val visible = allChannels
        if (visible.isEmpty()) return false
        val currentIndex = visible.indexOfFirst { it.key == currentChannel?.key }
        val nextIndex = if (currentIndex < 0) 0 else (currentIndex + step + visible.size) % visible.size
        playChannel(visible[nextIndex], enterFull = isFullscreen)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && isFullscreen) {
            if (!isAndroidTv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            setFullscreenUi(false)
            return true
        }
        if (isAndroidTv && (keyCode == KeyEvent.KEYCODE_CHANNEL_UP ||
                (keyCode == KeyEvent.KEYCODE_DPAD_UP && (isFullscreen || binding.playerContainer.hasFocus())))) {
            return changeChannel(-1) || super.onKeyDown(keyCode, event)
        }
        if (isAndroidTv && (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN ||
                (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && (isFullscreen || binding.playerContainer.hasFocus())))) {
            return changeChannel(1) || super.onKeyDown(keyCode, event)
        }
        if (isAndroidTv && keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) return changeChannel(-1)
        if (isAndroidTv && keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) return changeChannel(1)
        return super.onKeyDown(keyCode, event)
    }

    private fun releasePlayer() {
        mainHandler.removeCallbacks(bufferingTimeout)
        player?.release()
        player = null
        binding.playerView.player = null
    }

    override fun onStop() {
        wasPlayingBeforeStop = player?.isPlaying == true || player?.playWhenReady == true
        player?.pause()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (wasPlayingBeforeStop && player != null) {
            player?.playWhenReady = true
            player?.play()
        }
        wasPlayingBeforeStop = false
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }
}
