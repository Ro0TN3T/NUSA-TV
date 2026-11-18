package com.nusatv.androidtv

import android.os.Bundle
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
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
    private var onlyIndonesia = true
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
        binding.rvChannels.layoutManager = GridLayoutManager(this, if (isAndroidTv) 3 else 2)
        binding.rvChannels.adapter = adapter
    }

    private fun setupControls() {
        binding.btnFilterIndo.setOnClickListener {
            onlyIndonesia = !onlyIndonesia
            binding.btnFilterIndo.text = if (onlyIndonesia) "🇮🇩 TV Nasional" else "🌐 Semua Negara"
            binding.tvSectionTitle.text = if (onlyIndonesia) "🇮🇩 TV Nasional & Indonesia" else "🌐 Semua Channel"
            applyFilter()
        }

        binding.btnFullscreen.setOnClickListener {
            toggleFullscreen(!isFullscreen)
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
            val matchCountry = !onlyIndonesia || ch.country.equals("Indonesia", true)
            val matchQuery = query.isEmpty() || ch.name.lowercase().contains(query)
            matchCountry && matchQuery
        }
        adapter.submitList(filtered, currentChannel?.key)
        binding.tvStats.text = "${filtered.size} channel siap"
        if (isAndroidTv && !isFullscreen && filtered.isNotEmpty()) {
            binding.rvChannels.post {
                if (!binding.rvChannels.hasFocus()) {
                    binding.rvChannels.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
            }
        }
    }

    private fun playChannel(channel: TvChannel, enterFull: Boolean) {
        if (channel.sources.isEmpty()) return
        currentChannel = channel
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

        val hlsMediaSource = HlsMediaSource.Factory(dataSourceFactory)
            .setAllowChunklessPreparation(true)
            .createMediaSource(MediaItem.fromUri(candidate.url))

        val exoPlayer = ExoPlayer.Builder(this).build().apply {
            setMediaSource(hlsMediaSource)
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
                    tryNextSource(error.errorCodeName)
                }
            })
            prepare()
        }

        player = exoPlayer
        binding.playerView.player = exoPlayer
    }

    private fun tryNextSource(reason: String) {
        val ch = currentChannel ?: return
        if (currentSourceIndex + 1 < ch.sources.size) {
            currentSourceIndex++
            binding.tvStatus.text = "Gagal ($reason). Mencoba link ${currentSourceIndex + 1}/${ch.sources.size}..."
            startStream(ch, currentSourceIndex)
        } else {
            binding.tvLiveLabel.text = "● OFFLINE SEMENTARA"
            binding.tvStatus.text = "Semua sumber ${ch.name} sedang offline ($reason)."
            if (isFullscreen) {
                toggleFullscreen(false)
            }
        }
    }

    private fun stopPlayback() {
        releasePlayer()
        currentChannel = null
        adapter.setSelectedChannel(null)
        binding.tvLiveLabel.text = "● NUSA TV • SIAP MENONTON"
        binding.tvWelcomeTitle.text = "Pilih channel"
        binding.tvWelcomeDesc.text = "Pilih channel dengan sentuhan atau tombol OK pada remote TV."
        binding.tvStatus.text = "Pemutaran dihentikan."
    }

    private fun toggleFullscreen(enable: Boolean) {
        isFullscreen = enable
        if (enable) {
            binding.headerBar.visibility = View.GONE
            binding.welcomePanel.visibility = View.GONE
            binding.statsBar.visibility = View.GONE
            binding.rvChannels.visibility = View.GONE

            val params = binding.heroSection.layoutParams
            params.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            binding.heroSection.layoutParams = params
            val playerParams = binding.playerContainer.layoutParams
            playerParams.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            binding.playerContainer.layoutParams = playerParams
            binding.heroSection.setPadding(0, 0, 0, 0)
            binding.playerContainer.requestFocus()
        } else {
            binding.headerBar.visibility = View.VISIBLE
            binding.welcomePanel.visibility = View.VISIBLE
            binding.statsBar.visibility = View.VISIBLE
            binding.rvChannels.visibility = View.VISIBLE

            val density = resources.displayMetrics.density
            val params = binding.heroSection.layoutParams
            params.height = if (isAndroidTv) (230 * density).toInt()
                else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            binding.heroSection.layoutParams = params
            val playerParams = binding.playerContainer.layoutParams
            playerParams.height = if (isAndroidTv) android.view.ViewGroup.LayoutParams.MATCH_PARENT
                else (190 * density).toInt()
            binding.playerContainer.layoutParams = playerParams
            val padH = ((if (isAndroidTv) 24 else 12) * density).toInt()
            val padT = ((if (isAndroidTv) 12 else 10) * density).toInt()
            binding.heroSection.setPadding(padH, padT, padH, 0)
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
