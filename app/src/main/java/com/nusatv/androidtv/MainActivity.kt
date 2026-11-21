package com.nusatv.androidtv

import android.os.Bundle
import android.os.Build
import android.app.PictureInPictureParams
import android.util.Rational
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.media.AudioManager
import android.widget.ScrollView
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.Switch
import android.widget.Button
import android.content.res.Configuration
import android.view.View
import android.view.Gravity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.graphics.drawable.StateListDrawable
import android.widget.FrameLayout
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
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.GridLayoutManager
import com.nusatv.androidtv.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ChannelAdapter

    private var player: ExoPlayer? = null
    private var decoderManager: DecoderManager? = null
    private var allChannels: List<TvChannel> = emptyList()
    private var currentChannel: TvChannel? = null
    private var currentSourceIndex = 0
    private var selectedCategory = "Indonesia"
    private var filterGeneration = 0
    private var filterJob: Job? = null
    private var categoryDialog: AlertDialog? = null
    private var isFullscreen = false
    private var isAndroidTv = false
    private var wasPlayingBeforeStop = false
    private var transientRetryCount = 0
    private var isTryingFallback = false
    private var playbackGeneration = 0
    private var isRefreshingEpg = false
    private var fullscreenOverlay: View? = null
    private var playPauseControl: TextView? = null
    private val settingsPrefs by lazy { getSharedPreferences("nusa_settings", MODE_PRIVATE) }
    private var touchStartX = 0f
    private var touchStartY = 0f
    private val mainHandler = Handler(Looper.getMainLooper())
    private val playerOverlayHideRunnable = object : Runnable {
        override fun run() {
            val overlay = fullscreenOverlay ?: return
            if (overlay.hasFocus()) binding.playerContainer.requestFocus()
            overlay.visibility = View.GONE
        }
    }
    private val bufferingTimeout = Runnable {
        if (player?.playbackState == Player.STATE_BUFFERING) {
            tryNextSource("Buffering timeout setelah 15 detik")
        }
    }
    private val epgRefreshRunnable = object : Runnable {
        override fun run() {
            refreshEpg()
            mainHandler.postDelayed(this, 30 * 60 * 1000L)
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
        applyRotationSetting(settingsPrefs.getString("setting_rotation", "Auto") ?: "Auto")
        applyThemeSetting(settingsPrefs.getString("setting_theme", "Dark") ?: "Dark")

        allChannels = ChannelCache.channels
        if (allChannels.isEmpty()) {
            reloadPlaylists()
        } else {
            refreshEpg()
            applyFilter()
            val firstChannel = allChannels.firstOrNull { it.country == "Indonesia" } ?: allChannels.firstOrNull()
            firstChannel?.let { playChannel(it, enterFull = false) }
        }
        mainHandler.postDelayed(epgRefreshRunnable, 30 * 60 * 1000L)
    }

    private fun setupRecyclerView() {
        binding.rvChannels.setHasFixedSize(true)
        binding.rvChannels.itemAnimator = null
        binding.rvChannels.setItemViewCacheSize(4)
        adapter = ChannelAdapter { channel ->
            // Selecting a channel changes the stream in the embedded player only.
            playChannel(channel, enterFull = false)
        }
        binding.rvChannels.layoutManager = GridLayoutManager(this, if (isAndroidTv) 3 else 1)
        binding.rvChannels.adapter = adapter
    }

    private fun setupControls() {
        binding.btnFilterIndo.visibility = View.VISIBLE
        binding.btnFilterIndo.text = if (selectedCategory == "Events") "Events Live ▼" else "$selectedCategory ▼"
        binding.btnFilterIndo.setTextColor(Color.WHITE)

        // Keep the channel list directly under the server tabs on phone and TV.
        // Playback status and channel-count banners are intentionally hidden.
        binding.welcomePanel.visibility = View.GONE
        binding.statsBar.visibility = View.GONE
        binding.btnFilterIndo.setOnClickListener { showCategoryDialog() }
        binding.btnFullscreen.visibility = View.GONE
        binding.btnStop.visibility = View.GONE
        binding.btnReload.visibility = View.GONE
        binding.etSearch.visibility = View.GONE
        binding.btnFullscreen.setOnClickListener { requestLandscapeFullscreen() }

        binding.playerContainer.setOnClickListener {
            // A tap reveals the overlay; fullscreen is a separate explicit control.
            showPlayerOverlayTemporarily()
        }
        setupSwipeGestures()
        setupFullscreenOverlayControls()

        binding.btnReload.text = "⚙"
        binding.btnReload.contentDescription = "Pengaturan"
        binding.btnReload.setOnClickListener { showSettingsDialog() }

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
                refreshEpg()
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

    private fun refreshEpg() {
        if (allChannels.isEmpty() || isRefreshingEpg || isFinishing || isDestroyed) return
        isRefreshingEpg = true
        lifecycleScope.launch {
            try {
                val schedules = EpgRepository.loadSchedules()
                // Normalize once instead of scanning the entire EPG map for every channel.
                val normalizedSchedules = withContext(Dispatchers.Default) {
                    schedules.entries.associate { PlaylistRepository.canonicalName(it.key) to it.value }
                }
                val updated = withContext(Dispatchers.Default) {
                    allChannels.map { channel ->
                        val schedule = channel.tvgId?.let { schedules[it] ?: normalizedSchedules[PlaylistRepository.canonicalName(it)] }
                            ?: schedules[channel.key]
                            ?: normalizedSchedules[channel.key]
                        channel.copy(
                            currentProgramTitle = schedule?.current,
                            nextProgramTitle = schedule?.next
                        )
                    }
                }
                allChannels = updated
                if (allChannels.isNotEmpty()) applyFilter()
                binding.tvStatus.text = "Jadwal acara berhasil diperbarui"
            } catch (_: Exception) {
                // EPG is optional: live playback and channel list remain available.
                binding.tvStatus.text = "Jadwal EPG belum tersedia • Siaran tetap berjalan"
            } finally {
                isRefreshingEpg = false
            }
        }
    }

    // Filter on a worker thread so tapping a category never scans a large playlist on the UI thread.
    private fun applyFilter() {
        val requestId = ++filterGeneration
        val source = allChannels
        val category = selectedCategory
        // Cancel the previous scan instead of queuing multiple expensive filters on slow phones.
        filterJob?.cancel()
        filterJob = lifecycleScope.launch {
            val filteredChannels = withContext(Dispatchers.Default) {
                if (category == "Semua") source
                else {
                    val workerContext = currentCoroutineContext()
                    buildList {
                        source.forEachIndexed { index, channel ->
                            if ((index and 127) == 0) workerContext.ensureActive()
                            if (channelMatchesCategory(channel, category)) add(channel)
                        }
                    }
                }
            }
            if (requestId != filterGeneration || isFinishing || isDestroyed) return@launch
            adapter.submitList(filteredChannels, currentChannel?.key)
            binding.tvStats.text = "${filteredChannels.size} channel"
            val displayCategory = when (category) {
                "Semua" -> "Semua Channel"
                "Events" -> "Events Live"
                else -> category
            }
            binding.tvSectionTitle.text = displayCategory
            binding.tvSectionTitle.setTextColor(Color.WHITE)
            binding.btnFilterIndo.text = "$displayCategory ▼"
            updateServerTabs()
            if (isAndroidTv && !isFullscreen && filteredChannels.isNotEmpty()) {
                binding.rvChannels.post {
                    if (!binding.rvChannels.hasFocus()) {
                        binding.rvChannels.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    }
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

    private fun showCategoryDialog() {
        val labels = listOf("Semua Channel", "Events Live", "Indonesia", "Kids", "Knowledge",
            "Premium Movie", "Religi", "Sports", "News")
        val values = listOf("Semua", "Events", "Indonesia", "Kids", "Knowledge",
            "Premium Movie", "Religi", "Sports", "News")
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = rounded("#242631", "#414451", 22)
        }
        val title = TextView(this).apply {
            text = "Pilih Kategori"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(2), dp(4), dp(12))
        }
        panel.addView(title)
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            background = rounded("#1D2029", "#393D49", 16)
        }
        labels.forEachIndexed { index, label ->
            val selected = values[index] == selectedCategory
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                this.gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                background = rounded(if (selected) "#332A49" else "#1D2029",
                    if (selected) "#B99AFB" else "#1D2029", 12)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedCategory = values[index]
                    categoryDialog?.dismiss()
                    applyFilter()
                }
            }
            val name = TextView(this).apply {
                // Keep category names white; only the LIVE badge is red.
                text = (if (selected) "●   " else "○   ") +
                    if (values[index] == "Events") "Events" else label
                textSize = 15f
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (selected) Color.parseColor("#D0B5FF") else Color.WHITE)
                this.gravity = Gravity.CENTER_VERTICAL
            }
            item.addView(name, LinearLayout.LayoutParams(0, -1, 1f))
            if (values[index] == "Events") {
                val liveBadge = TextView(this).apply {
                    text = "LIVE"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(5), dp(12), dp(5))
                    background = rounded("#FF3038", "#FF3038", 20)
                    isClickable = false
                }
                item.addView(liveBadge, LinearLayout.LayoutParams(-2, -2))
            }
            list.addView(item, LinearLayout.LayoutParams(-1, dp(48)).apply {
                bottomMargin = dp(5)
            })
        }
        scroll.addView(list)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, dp(360)))
        val dialog = AlertDialog.Builder(this).setView(panel)
            .setNegativeButton("Tutup", null).create()
        categoryDialog = dialog
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.parseColor("#CBB1FF"))
        }
        dialog.show()
    }

    private fun showSettingsDialog() {
        val purple = Color.parseColor("#C8A9FF")
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            background = rounded("#30323D", "#30323D", 24)
        }
        val title = TextView(this).apply {
            text = "Pengaturan Fitur"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(14))
        }
        panel.addView(title)
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded("#22252F", "#414451", 18)
        }
        fun addSwitchRow(label: String, description: String, key: String, defaultValue: Boolean): Switch {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(9), dp(4), dp(9))
            }
            val sw = Switch(this).apply {
                text = label
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                isChecked = settingsPrefs.getBoolean(key, defaultValue)
                buttonTintList = android.content.res.ColorStateList.valueOf(purple)
            }
            val desc = TextView(this).apply {
                text = description
                textSize = 13f
                setTextColor(Color.parseColor("#C0C1CA"))
                setPadding(0, dp(3), dp(4), 0)
            }
            row.addView(sw)
            row.addView(desc)
            inner.addView(row)
            return sw
        }
        val pipSwitch = addSwitchRow("Picture-in-Picture (PiP)",
            "Izinkan video tetap berjalan melayang.", "setting_pip", false)
        val gestureSwitch = addSwitchRow("Gesture Swipe Layar",
            "Gunakan usapan vertikal di sisi kiri untuk kecerahan dan sisi kanan untuk volume.",
            "setting_gesture", true)

        fun addDivider() {
            val line = View(this).apply { setBackgroundColor(Color.parseColor("#454956")) }
            inner.addView(line, LinearLayout.LayoutParams(-1, dp(1)).apply {
                topMargin = dp(5); bottomMargin = dp(5)
            })
        }
        addDivider()
        val rotationTitle = TextView(this).apply {
            text = "Rotasi Layar"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(8), 0, dp(8))
        }
        inner.addView(rotationTitle)
        val rotation = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rotationValues = listOf("Portrait", "Landscape", "Auto")
        val rotationIds = listOf(View.generateViewId(), View.generateViewId(), View.generateViewId())
        rotationValues.forEachIndexed { index, label ->
            val rb = RadioButton(this).apply {
                id = rotationIds[index]
                text = label
                textSize = 12f
                setTextColor(purple)
                buttonTintList = android.content.res.ColorStateList.valueOf(purple)
                layoutParams = RadioGroup.LayoutParams(0, dp(44), 1f)
            }
            rotation.addView(rb)
        }
        val savedRotation = settingsPrefs.getString("setting_rotation", "Auto")
        rotation.check(rotationIds[rotationValues.indexOf(savedRotation).takeIf { it >= 0 } ?: 2])
        inner.addView(rotation)
        addDivider()
        val themeTitle = TextView(this).apply {
            text = "Tema Aplikasi"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(8), 0, dp(8))
        }
        inner.addView(themeTitle)
        val theme = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val themeValues = listOf("Light", "Dark", "Auto")
        val themeIds = themeValues.map { View.generateViewId() }
        themeValues.forEachIndexed { index, label ->
            theme.addView(RadioButton(this).apply {
                id = themeIds[index]
                text = label
                textSize = 12f
                setTextColor(purple)
                buttonTintList = android.content.res.ColorStateList.valueOf(purple)
                layoutParams = RadioGroup.LayoutParams(0, dp(44), 1f)
            })
        }
        val savedTheme = settingsPrefs.getString("setting_theme", "Dark")
        theme.check(themeIds[themeValues.indexOf(savedTheme).takeIf { it >= 0 } ?: 1])
        inner.addView(theme)
        panel.addView(inner)
        val reload = Button(this).apply {
            text = "Perbarui Playlist"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded("#3A3D49", "#555968", 18)
            setOnClickListener {
                categoryDialog?.dismiss()
                reloadPlaylists()
            }
        }
        panel.addView(reload, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        val cancel = Button(this).apply {
            text = "Batal"; isAllCaps = false; setTextColor(Color.WHITE)
            background = rounded("#252832", "#252832", 28)
        }
        val apply = Button(this).apply {
            text = "Apply"; isAllCaps = false; setTextColor(Color.WHITE)
            background = rounded("#078DCE", "#078DCE", 28)
        }
        actions.addView(cancel, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(8) })
        actions.addView(apply, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(8) })
        panel.addView(actions)

        val dialog = AlertDialog.Builder(this).setView(panel).create()
        categoryDialog = dialog
        cancel.setOnClickListener { dialog.dismiss() }
        apply.setOnClickListener {
            val rotationChoice = rotationValues[rotationIds.indexOf(rotation.checkedRadioButtonId).coerceAtLeast(0)]
            val themeChoice = themeValues[themeIds.indexOf(theme.checkedRadioButtonId).coerceAtLeast(0)]
            settingsPrefs.edit()
                .putBoolean("setting_pip", pipSwitch.isChecked)
                .putBoolean("setting_gesture", gestureSwitch.isChecked)
                .putString("setting_rotation", rotationChoice)
                .putString("setting_theme", themeChoice)
                .apply()
            applyRotationSetting(rotationChoice)
            applyThemeSetting(themeChoice)
            dialog.dismiss()
        }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    /** ION TV-style two-row controls over the video. Subtitle selection is intentionally omitted. */
    private fun setupFullscreenOverlayControls() {
        val overlay = FrameLayout(this).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun makeRow(gravity: Int): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setBackground(GradientDrawable().apply {
                setColor(Color.parseColor("#66070B12"))
                cornerRadius = dp(24).toFloat()
            })
            elevation = dp(5).toFloat()
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                gravity
            )
        }

        fun overlayButton(symbol: String, description: String, action: () -> Unit,
                          prominent: Boolean = false): TextView {
            val focused = GradientDrawable().apply {
                setColor(Color.parseColor("#5539BDF0"))
                setStroke(dp(2), Color.WHITE)
                cornerRadius = dp(28).toFloat()
            }
            val normal = GradientDrawable().apply {
                setColor(if (prominent) Color.WHITE else Color.TRANSPARENT)
                cornerRadius = dp(28).toFloat()
            }
            val states = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(android.R.attr.state_pressed), focused)
                addState(intArrayOf(), normal)
            }
            return TextView(this).apply {
                text = symbol
                textSize = if (prominent) 25f else 21f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (prominent) Color.BLACK else Color.WHITE)
                contentDescription = description
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                minWidth = if (prominent) dp(62) else dp(48)
                minHeight = if (prominent) dp(62) else dp(48)
                background = states
                setOnClickListener { action(); showPlayerOverlayTemporarily() }
                layoutParams = LinearLayout.LayoutParams(
                    if (prominent) dp(62) else dp(48),
                    if (prominent) dp(62) else dp(48)
                ).apply {
                    marginStart = dp(5)
                    marginEnd = dp(5)
                }
            }
        }

        lateinit var playPause: TextView
        lateinit var mute: TextView

        // Upper row: playback options, like the compact controls along the top of ION TV.
        val topRow = makeRow(Gravity.TOP or Gravity.END)
        topRow.addView(overlayButton("♫", "Pilih audio track", action = {
            showTrackSelectionDialog(C.TRACK_TYPE_AUDIO)
        }))
        topRow.addView(overlayButton("1×", "Kecepatan pemutaran", action = { showPlaybackSpeedDialog() }))
        topRow.addView(overlayButton("HD", "Kualitas video jika tersedia", action = { showVideoQualityDialog() }))
        topRow.addView(overlayButton("▣", "Ukuran tampilan video", action = { showVideoResizeDialog() }))
        topRow.addView(overlayButton("⚙", "Pengaturan player", action = { showSettingsDialog() }))

        // Lower row: channel navigation and the large central play/pause control.
        val bottomRow = makeRow(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        bottomRow.addView(overlayButton("|◀", "Channel sebelumnya", action = { changeChannel(-1) }))
        playPause = overlayButton("Ⅱ", "Putar atau jeda", {
            val exo = player ?: return@overlayButton
            if (exo.isPlaying || exo.playWhenReady) exo.pause() else exo.play()
            playPause.text = if (exo.isPlaying || exo.playWhenReady) "Ⅱ" else "▶"
        }, prominent = true)
        playPauseControl = playPause
        bottomRow.addView(playPause)
        bottomRow.addView(overlayButton("▶|", "Channel berikutnya", action = { changeChannel(1) }))
        mute = overlayButton("🔊", "Bisukan atau aktifkan suara", action = {
            val exo = player ?: return@overlayButton
            val muted = exo.volume > 0f
            exo.volume = if (muted) 0f else 1f
            muteLabelUpdate(mute, muted)
        })
        bottomRow.addView(mute)
        bottomRow.addView(overlayButton("⛶", "Masuk atau keluar layar penuh", action = {
            toggleFullscreen(!isFullscreen)
        }))

        overlay.addView(topRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply { setMargins(dp(8), dp(8), dp(8), 0) })
        overlay.addView(bottomRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { setMargins(dp(8), 0, dp(8), dp(10)) })

        overlay.visibility = View.VISIBLE
        binding.playerContainer.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        fullscreenOverlay = overlay
        showPlayerOverlayTemporarily()
    }

    private fun showVideoResizeDialog() {
        val modes = arrayOf("Fit • tampilkan seluruh gambar", "Fill • isi layar", "Zoom • perbesar")
        val values = intArrayOf(
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL,
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        )
        val selected = values.indexOf(binding.playerView.resizeMode).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Ukuran video")
            .setSingleChoiceItems(modes, selected) { dialog, which ->
                binding.playerView.resizeMode = values[which]
                dialog.dismiss()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showPlayerOverlayTemporarily() {
        fullscreenOverlay?.visibility = View.VISIBLE
        mainHandler.removeCallbacks(playerOverlayHideRunnable)
        mainHandler.postDelayed(playerOverlayHideRunnable, 4000L)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (player != null) showPlayerOverlayTemporarily()
    }

    private fun muteLabelUpdate(button: TextView, muted: Boolean) {
        button.text = if (muted) "🔇" else "🔊"
        button.contentDescription = if (muted) "Aktifkan suara" else "Bisukan suara"
    }

    @OptIn(UnstableApi::class)
    private fun showVideoQualityDialog() {
        val exo = player ?: return
        data class QualityOption(val label: String, val group: androidx.media3.common.TrackGroup, val index: Int)
        val options = mutableListOf<QualityOption>()
        exo.currentTracks.groups.forEach { group ->
            if (group.type == C.TRACK_TYPE_VIDEO) {
                for (index in 0 until group.length) {
                    if (!group.isTrackSupported(index)) continue
                    val format = group.getTrackFormat(index)
                    if (format.height > 0) {
                        val label = if (format.width > 0) "${format.height}p (${format.width}×${format.height})" else "${format.height}p"
                        options.add(QualityOption(label, group.mediaTrackGroup, index))
                    }
                }
            }
        }
        val unique = options.distinctBy { it.label }.sortedByDescending { it.group.getFormat(it.index).height }
        val labels = listOf("Otomatis (adaptif)") + unique.map { it.label }
        AlertDialog.Builder(this)
            .setTitle("Kualitas / Resolusi video")
            .setItems(labels.toTypedArray()) { _, which ->
                val builder = exo.trackSelectionParameters.buildUpon()
                if (which == 0) {
                    builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                } else {
                    val selected = unique[which - 1]
                    builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    builder.addOverride(TrackSelectionOverride(selected.group, listOf(selected.index)))
                }
                exo.trackSelectionParameters = builder.build()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    @OptIn(UnstableApi::class)
    private fun showTrackSelectionDialog(trackType: Int) {
        val exo = player ?: return
        val isText = trackType == C.TRACK_TYPE_TEXT
        val title = if (isText) "Subtitle" else "Audio"
        data class TrackOption(val label: String, val group: androidx.media3.common.TrackGroup?, val index: Int)
        val options = mutableListOf<TrackOption>()
        options.add(TrackOption(if (isText) "Nonaktif" else "Otomatis", null, -1))
        exo.currentTracks.groups.forEach { group ->
            if (group.type == trackType) {
                for (index in 0 until group.length) {
                    if (!group.isTrackSupported(index)) continue
                    val format = group.getTrackFormat(index)
                    val label = buildString {
                        append(format.label?.takeIf { it.isNotBlank() }
                            ?: format.language?.takeIf { it.isNotBlank() }
                            ?: "Track ${index + 1}")
                        if (!isText && format.channelCount > 0) append(" • ${format.channelCount}ch")
                        if (format.language != null && format.label != null) append(" (${format.language})")
                    }
                    options.add(TrackOption(label, group.mediaTrackGroup, index))
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Pilih $title")
            .setItems(options.map { it.label }.toTypedArray()) { _, which ->
                val selected = options[which]
                val builder = exo.trackSelectionParameters.buildUpon()
                builder.clearOverridesOfType(trackType)
                if (isText) builder.setTrackTypeDisabled(trackType, selected.group == null)
                else builder.setTrackTypeDisabled(trackType, false)
                if (selected.group != null) {
                    builder.addOverride(TrackSelectionOverride(selected.group, listOf(selected.index)))
                }
                exo.trackSelectionParameters = builder.build()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showPlaybackSpeedDialog() {
        val exo = player ?: return
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val labels = speeds.map { if (it == 1.0f) "Normal (1×)" else "${it}×" }
        AlertDialog.Builder(this)
            .setTitle("Kecepatan pemutaran")
            .setItems(labels.toTypedArray()) { _, which ->
                exo.setPlaybackSpeed(speeds[which])
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showDecoderDialog() {
        val manager = decoderManager ?: return
        val options = listOf(
            "Video • Otomatis (disarankan)", "Video • Hardware", "Video • Software", "Video • FFmpeg",
            "Audio • Otomatis (disarankan)", "Audio • Hardware", "Audio • Software", "Audio • FFmpeg"
        )
        AlertDialog.Builder(this)
            .setTitle("Mode Decoder")
            .setItems(options.toTypedArray()) { _, which ->
                val mode = when (which % 4) {
                    0 -> DecoderMode.AUTO
                    1 -> DecoderMode.HARDWARE
                    2 -> DecoderMode.SOFTWARE
                    else -> DecoderMode.FFMPEG
                }
                if (which < 4) manager.selectVideoDecoder(mode)
                else manager.selectAudioDecoder(mode)
                binding.tvStatus.text = "Decoder ${if (which < 4) "video" else "audio"}: ${mode.name}"
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun setupSwipeGestures() {
        binding.playerView.setOnTouchListener { _, event ->
            if (!settingsPrefs.getBoolean("setting_gesture", true)) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    showPlayerOverlayTemporarily()
                    touchStartX = event.x
                    touchStartY = event.y
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val deltaY = touchStartY - event.y
                    if (kotlin.math.abs(deltaY) < dp(35)) return@setOnTouchListener false
                    val direction = if (deltaY > 0) 1 else -1
                    if (touchStartX < binding.playerView.width / 2f) {
                        val attrs = window.attributes
                        val current = if (attrs.screenBrightness < 0f) 0.5f else attrs.screenBrightness
                        attrs.screenBrightness = (current + direction * 0.08f).coerceIn(0.05f, 1f)
                        window.attributes = attrs
                    } else {
                        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                            AudioManager.FLAG_SHOW_UI)
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            settingsPrefs.getBoolean("setting_pip", false) &&
            (player?.isPlaying == true || player?.playWhenReady == true)) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            runCatching { enterPictureInPictureMode(params) }
        }
    }

    private fun applyRotationSetting(value: String) {
        if (isAndroidTv) return
        requestedOrientation = when (value) {
            "Portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "Landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun applyThemeSetting(value: String) {
        val dark = when (value) {
            "Light" -> false
            "Auto" -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
            else -> true
        }
        binding.root.setBackgroundColor(Color.parseColor(if (dark) "#090A11" else "#F2F3F7"))
        binding.headerBar.setBackgroundColor(Color.parseColor(if (dark) "#141622" else "#FFFFFF"))
        findViewById<View>(R.id.serverTabsScroll)?.setBackgroundColor(Color.parseColor(if (dark) "#141622" else "#FFFFFF"))
        binding.rvChannels.setBackgroundColor(Color.parseColor(if (dark) "#090A11" else "#F2F3F7"))
    }

    private fun channelMatchesCategory(channel: TvChannel, category: String): Boolean {
        val name = channel.name.lowercase()
        val groups = channel.categories.joinToString(" ").lowercase()
        val text = "$name $groups"
        return when (category) {
            "Indonesia" -> channel.country.equals("Indonesia", true) ||
                text.contains("indonesia") || text.contains("nasional")
            // Events Live also includes channels identified by the XMLTV feed.
            "Events" -> text.contains("event") || text.contains("live event") ||
                !channel.currentProgramTitle.isNullOrBlank() ||
                !channel.nextProgramTitle.isNullOrBlank()
            "Sports" -> listOf("sport", "sports", "olahraga", "football", "soccer", "bola").any { text.contains(it) }
            "Kids" -> listOf("kids", "kid", "anak", "children", "cartoon", "animation", "kartun").any { text.contains(it) }
            "Premium Movie" -> listOf("movie", "movies", "film", "cinema", "blockbuster", "premium").any { text.contains(it) }
            "Religi" -> listOf("religi", "religion", "islam", "muslim", "kristen", "rohani").any { text.contains(it) }
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
        binding.tvLiveLabel.setTextColor(Color.parseColor("#FF3B4F"))
        binding.tvWelcomeTitle.text = channel.name
        binding.tvWelcomeDesc.text = buildString {
            val now = channel.currentProgramTitle
            val next = channel.nextProgramTitle
            if (!now.isNullOrBlank()) append("Sekarang: $now")
            if (!next.isNullOrBlank()) {
                if (isNotEmpty()) append("  •  ")
                append("Berikutnya: $next")
            }
            if (isEmpty()) append(channel.programTitle?.takeIf { it.isNotBlank() }
                ?: "Sumber: ${candidate.sourceName} • Menunggu info EPG")
        }
        binding.tvStatus.text = "Menghubungkan ke ${channel.name} (${sourceIdx + 1}/${channel.sources.size})..."

        releasePlayer()
        playbackGeneration += 1
        val activeGeneration = playbackGeneration

        // These properties are attached to the OkHttp-backed Media3 DataSource.
        // HLS uses the same factory for the master/media playlists and media segments.
        val headersMap = linkedMapOf<String, String>()
        candidate.userAgent.takeIf { it.isNotBlank() }?.let { headersMap["User-Agent"] = it }
        if (runCatching { java.net.URI(candidate.url).host.orEmpty().contains("rctiplus.com", true) }.getOrDefault(false)) {
            headersMap["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"
            headersMap["Referer"] = "https://www.rctiplus.com/"
            headersMap["Origin"] = "https://www.rctiplus.com"
        }
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

        // Add FFmpeg extension renderers after Android platform renderers. This keeps
        // hardware decoding preferred, while FFmpeg is available as a fallback for
        // formats that the device's MediaCodec cannot decode.
        val decoderManagerForPlayer = DecoderManager()
        val ffmpegRenderers = NextRenderersFactory(this)
            .setDecoderManager(decoderManagerForPlayer)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val trackSelector = DefaultTrackSelector(this)

        val exoPlayer = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setRenderersFactory(ffmpegRenderers)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                // DecoderManager must attach before prepare and requires DefaultTrackSelector.
                decoderManagerForPlayer.attach(this)
                decoderManager = decoderManagerForPlayer
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
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        playPauseControl?.text = if (playWhenReady) "Ⅱ" else "▶"
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (activeGeneration != playbackGeneration || currentChannel?.key != channel.key) return
                        when (playbackState) {
                            Player.STATE_READY -> {
                                mainHandler.removeCallbacks(bufferingTimeout)
                                transientRetryCount = 0
                                isTryingFallback = false
                                binding.tvLiveLabel.text = "● LIVE • SEDANG DIPUTAR"
                                binding.tvLiveLabel.setTextColor(Color.parseColor("#FF3B4F"))
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
            binding.tvLiveLabel.setTextColor(Color.parseColor("#FF3B4F"))
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
            if (!isAndroidTv) applyRotationSetting(settingsPrefs.getString("setting_rotation", "Auto") ?: "Auto")
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
        if (!isAndroidTv) {
            // Fullscreen video is always landscape, even when the phone was
            // previously held in portrait. SENSOR_LANDSCAPE also permits both
            // landscape directions while preventing a return to portrait.
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        setFullscreenUi(true)
    }

    private fun setFullscreenUi(enable: Boolean) {
        isFullscreen = enable
        if (enable) {
            fullscreenOverlay?.visibility = View.VISIBLE
            showPlayerOverlayTemporarily()
            binding.headerBar.visibility = View.GONE
            binding.welcomePanel.visibility = View.GONE
            binding.statsBar.visibility = View.GONE
            binding.rvChannels.visibility = View.GONE
            binding.serverTabsScroll?.visibility = View.GONE
            binding.etSearch.visibility = View.GONE
            if (isAndroidTv) {
                binding.heroSection?.apply {
                    setPadding(0, 0, 0, 0)
                    layoutParams = layoutParams.apply {
                        width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    }
                }
                binding.playerContainer.layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            } else {
                binding.playerContainer.layoutParams = binding.playerContainer.layoutParams.apply {
                    width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                }
            }
            // Keep the complete broadcast frame visible in fullscreen. ZOOM crops
            // the edges and makes embedded channel watermarks appear oversized.
            binding.playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        } else {
            fullscreenOverlay?.visibility = View.VISIBLE
            showPlayerOverlayTemporarily()
            binding.headerBar.visibility = View.VISIBLE
            binding.welcomePanel.visibility = View.GONE
            binding.statsBar.visibility = View.GONE
            binding.rvChannels.visibility = View.VISIBLE
            binding.serverTabsScroll?.visibility = View.VISIBLE
            binding.etSearch.visibility = View.GONE
            val density = resources.displayMetrics.density
            if (isAndroidTv) {
                binding.heroSection?.apply {
                    setPadding(dp(24), dp(12), dp(24), 0)
                    layoutParams = layoutParams.apply {
                        width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        height = dp(260)
                    }
                }
                binding.playerContainer.layoutParams = LinearLayout.LayoutParams(0, -1, 1.5f)
            } else {
                binding.playerContainer.layoutParams = binding.playerContainer.layoutParams.apply {
                    width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    height = (210 * density).toInt()
                }
            }
            binding.playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun toggleFullscreen(enable: Boolean) {
        if (enable) requestLandscapeFullscreen() else {
            if (!isAndroidTv) applyRotationSetting(settingsPrefs.getString("setting_rotation", "Auto") ?: "Auto")
            setFullscreenUi(false)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isAndroidTv) return

        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        val hasActiveVideo = player != null &&
            (player?.isPlaying == true || player?.playWhenReady == true)

        // When the user rotates a playing phone to landscape, enter fullscreen
        // automatically. Do not force fullscreen merely because the app starts
        // in landscape with no channel playing.
        if (isLandscape && hasActiveVideo && !isFullscreen) {
            requestLandscapeFullscreen()
            return
        }

        // Configuration changes during fullscreen must preserve the fullscreen
        // player UI. The requested orientation remains landscape-only.
        if (isFullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            setFullscreenUi(true)
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
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showSettingsDialog()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_SPACE) {
            val exo = player ?: return super.onKeyDown(keyCode, event)
            if (exo.isPlaying) exo.pause() else exo.play()
            showPlayerOverlayTemporarily()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && isFullscreen) {
            if (!isAndroidTv) applyRotationSetting(settingsPrefs.getString("setting_rotation", "Auto") ?: "Auto")
            setFullscreenUi(false)
            return true
        }
        if (isAndroidTv && keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
            return changeChannel(-1) || super.onKeyDown(keyCode, event)
        }
        if (isAndroidTv && keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            return changeChannel(1) || super.onKeyDown(keyCode, event)
        }
        if (isAndroidTv && keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) return changeChannel(-1)
        if (isAndroidTv && keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) return changeChannel(1)
        return super.onKeyDown(keyCode, event)
    }

    private fun releasePlayer() {
        mainHandler.removeCallbacks(bufferingTimeout)
        decoderManager?.detach()
        decoderManager = null
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
        mainHandler.removeCallbacks(epgRefreshRunnable)
        categoryDialog?.dismiss()
        filterJob?.cancel()
        releasePlayer()
        super.onDestroy()
    }
}
