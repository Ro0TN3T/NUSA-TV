package com.nusatv.androidtv

import android.os.Bundle
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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.GridLayoutManager
import com.nusatv.androidtv.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isAndroidTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        requestedOrientation = if (isAndroidTv) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
            playChannel(channel, enterFull = true)
        }
        binding.rvChannels.layoutManager = GridLayoutManager(this, if (isAndroidTv) 3 else 1)
        binding.rvChannels.adapter = adapter
    }

    private fun setupControls() {
        binding.btnFilterIndo.text = "Indonesia ▾"
        binding.btnFilterIndo.setOnClickListener { showCategoryDialog() }

        binding.btnFullscreen.setOnClickListener {
            toggleFullscreen(!isFullscreen)
        }

        binding.btnTabAll?.setOnClickListener {
            selectedCategory = "Semua"
            applyFilter()
        }
        binding.btnTabNews?.setOnClickListener {
            selectedCategory = "News"
            applyFilter()
        }
        binding.btnTabKids?.setOnClickListener {
            selectedCategory = "Kids"
            applyFilter()
        }

        binding.playerContainer.setOnClickListener {
            toggleFullscreen(!isFullscreen)
        }

        binding.btnReload.setOnClickListener {
            reloadPlaylists()
        }

        binding.btnStop.setOnClickListener {
            stopPlayback()
        }

        binding.btnRetry.setOnClickListener {
            currentChannel?.let { ch ->
                if (ch.sources.size > 1) {
                    currentSourceIndex = (currentSourceIndex + 1) % ch.sources.size
                }
                startStream(ch, currentSourceIndex)
            }
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun reloadPlaylists() {
        binding.btnReload.isEnabled = false
        binding.tvStatus.text = "Memperbarui playlist TV Nasional & global..."
        lifecycleScope.launch {
            try {
                allChannels = PlaylistRepository.loadAllChannels()
                ChannelCache.channels = allChannels
                applyFilter()
                binding.tvStatus.text = "${allChannels.size} channel dimuat dari GitHub."
            } catch (error: Exception) {
                binding.tvStatus.text = "Gagal memuat playlist GitHub. Periksa koneksi internet."
                if (allChannels.isEmpty()) adapter.submitList(emptyList())
            } finally {
                binding.btnReload.isEnabled = true
            }
        }
    }

    private fun applyFilter() {
        val query = binding.etSearch.text.toString().trim().lowercase()
        val filtered = allChannels.filter { ch ->
            val matchCategory = selectedCategory == "Semua" || channelMatchesCategory(ch, selectedCategory)
            val matchQuery = query.isEmpty() || ch.name.lowercase().contains(query) ||
                ch.categories.any { it.lowercase().contains(query) }
            matchCategory && matchQuery
        }
        adapter.submitList(filtered, currentChannel?.key)
        binding.tvStats.text = "${filtered.size} channel"
        binding.tvSectionTitle.text = when (selectedCategory) {
            "Semua" -> "Semua Channel"
            "Indonesia" -> "TV Nasional & Indonesia"
            "News" -> "Berita TV"
            "Kids" -> "TV Anak"
            else -> selectedCategory
        }
        binding.btnFilterIndo.text = when (selectedCategory) {
            "News" -> "Berita TV ▾"
            "Kids" -> "TV Anak ▾"
            else -> "$selectedCategory ▾"
        }

        val activeTab = android.graphics.Color.parseColor("#0797C2")
        val idleTab = android.graphics.Color.parseColor("#202A36")
        binding.btnTabAll?.setBackgroundColor(if (selectedCategory == "Semua" || selectedCategory == "Indonesia") activeTab else idleTab)
        binding.btnTabNews?.setBackgroundColor(if (selectedCategory == "News") activeTab else idleTab)
        binding.btnTabKids?.setBackgroundColor(if (selectedCategory == "Kids") activeTab else idleTab)
        if (isAndroidTv && !isFullscreen && filtered.isNotEmpty()) {
            binding.rvChannels.post {
                if (!binding.rvChannels.hasFocus()) {
                    binding.rvChannels.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
            }
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
            background = rounded("#07182B", "#14549A", 22)
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
                    (option.code == "ALL" && selectedCategory == "Semua")) rounded("#183B5C", "#315B85", 8)
                    else rounded("#0A1A2B", "#24384C", 0)
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
            setTextColor(Color.BLACK)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(12), dp(26), dp(12))
            background = rounded("#FFFFFF", "#FFFFFF", 28)
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        currentSourceIndex = 0
        adapter.setSelectedChannel(channel.key)

        if (enterFull) {
            toggleFullscreen(true)
        }
        startStream(channel, 0)
    }

    @OptIn(UnstableApi::class)
    private fun startStream(channel: TvChannel, sourceIdx: Int) {
        val candidate = channel.sources.getOrNull(sourceIdx) ?: return

        binding.tvLiveLabel.text = "● LIVE • SEDANG MEMUAT"
        binding.tvWelcomeTitle.text = channel.name
        binding.tvWelcomeDesc.text = "Sumber: ${candidate.sourceName}"
        binding.tvStatus.text = "Menghubungkan ke ${channel.name} (${sourceIdx + 1}/${channel.sources.size})..."

        releasePlayer()

        val headersMap = mutableMapOf<String, String>()
        headersMap["User-Agent"] = candidate.userAgent
        candidate.referer?.let { headersMap["Referer"] = it }
        candidate.origin?.let { headersMap["Origin"] = it }

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setDefaultRequestProperties(headersMap)

        // DefaultMediaSourceFactory memilih HLS, DASH, atau progressive
        // berdasarkan URI/MIME type MediaItem. Header diterapkan ke manifest,
        // segment, dan request media melalui OkHttpDataSource.
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

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
            setMediaItem(mediaItem)
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            binding.tvLiveLabel.text = "● LIVE • SEDANG DIPUTAR"
                            binding.tvStatus.text = "LIVE • ${candidate.sourceName}"
                        }
                        Player.STATE_BUFFERING -> {
                            binding.tvStatus.text = "Buffering ${channel.name}..."
                        }
                        Player.STATE_ENDED -> {
                            tryNextSource("Siaran berakhir")
                        }
                        else -> Unit
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val detail = formatPlaybackError(error)
                    Log.e("NUSA-TV-PLAYER", "Playback failed: channel=${channel.name}, source=${candidate.sourceName}, host=${runCatching { java.net.URI(candidate.url).host }.getOrNull()}, detail=$detail", error)
                    tryNextSource(detail)
                }
            })
            prepare()
        }

        player = exoPlayer
        binding.playerView.player = exoPlayer
    }

    /** Convert Media3/HTTP failures into a useful, privacy-safe diagnostic message. */
    private fun formatPlaybackError(error: PlaybackException): String {
        var current: Throwable? = error
        var httpCode: Int? = null
        var httpMessage: String? = null
        var deepestMessage: String? = null
        val visited = HashSet<Throwable>()

        while (current != null && visited.add(current)) {
            if (!current.message.isNullOrBlank()) deepestMessage = current.message
            when (current) {
                is HttpDataSource.InvalidResponseCodeException -> {
                    httpCode = current.responseCode
                    httpMessage = current.message
                }
            }
            current = current.cause
        }

        val detail = when {
            httpCode != null -> "HTTP $httpCode${httpMessage?.let { ": $it" } ?: ""}"
            deepestMessage?.contains("UnknownHost", true) == true -> "DNS / domain tidak ditemukan"
            deepestMessage?.contains("timeout", true) == true -> "Koneksi timeout"
            deepestMessage?.contains("Cleartext", true) == true -> "HTTP tidak aman diblokir Android"
            else -> deepestMessage?.take(180) ?: error.errorCodeName
        }
        return "${error.errorCodeName}: $detail"
    }

    private fun tryNextSource(reason: String) {
        val ch = currentChannel ?: return
        if (currentSourceIndex + 1 < ch.sources.size) {
            currentSourceIndex++
            binding.tvStatus.text = "Gagal ($reason). Mencoba link ${currentSourceIndex + 1}/${ch.sources.size}..."
            startStream(ch, currentSourceIndex)
        } else {
            binding.tvLiveLabel.text = "● GAGAL DIPUTAR"
            binding.tvStatus.text = "Gagal memutar ${ch.name}. Detail: $reason. Periksa koneksi atau coba lagi."
            if (isFullscreen) {
                toggleFullscreen(false)
            }
        }
    }

    private fun stopPlayback() {
        releasePlayer()
        currentChannel = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        adapter.setSelectedChannel(null)
        binding.tvLiveLabel.text = "● NUSA TV • SIAP MENONTON"
        binding.tvWelcomeTitle.text = "Pilih channel"
        binding.tvWelcomeDesc.text = "Pilih channel dengan sentuhan atau tombol OK pada remote TV."
        binding.tvStatus.text = "Pemutaran dihentikan."
    }

    private fun toggleFullscreen(enable: Boolean) {
        isFullscreen = enable
        if (!isAndroidTv) {
            requestedOrientation = if (enable) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (enable) {
            binding.headerBar.visibility = View.GONE
            binding.welcomePanel.visibility = View.GONE
            binding.statsBar.visibility = View.GONE
            binding.rvChannels.visibility = View.GONE

            binding.heroSection?.let { hero ->
                val params = hero.layoutParams
                params.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                hero.layoutParams = params
                hero.setPadding(0, 0, 0, 0)
            }
            val playerParams = binding.playerContainer.layoutParams
            playerParams.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            binding.playerContainer.layoutParams = playerParams
            binding.playerContainer.requestFocus()
        } else {
            binding.headerBar.visibility = View.VISIBLE
            binding.welcomePanel.visibility = View.VISIBLE
            binding.statsBar.visibility = View.VISIBLE
            binding.rvChannels.visibility = View.VISIBLE

            val density = resources.displayMetrics.density
            binding.heroSection?.let { hero ->
                val params = hero.layoutParams
                params.height = if (isAndroidTv) (230 * density).toInt()
                    else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                hero.layoutParams = params
                val padH = ((if (isAndroidTv) 24 else 12) * density).toInt()
                val padT = ((if (isAndroidTv) 12 else 10) * density).toInt()
                hero.setPadding(padH, padT, padH, 0)
            }
            val playerParams = binding.playerContainer.layoutParams
            playerParams.height = if (isAndroidTv) android.view.ViewGroup.LayoutParams.MATCH_PARENT
                else (190 * density).toInt()
            binding.playerContainer.layoutParams = playerParams
            binding.rvChannels.requestFocus()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && isFullscreen) {
            toggleFullscreen(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        binding.playerView.player = null
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }
}
