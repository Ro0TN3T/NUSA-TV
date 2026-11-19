package com.nusatv.androidtv

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nusatv.androidtv.databinding.ActivitySplashBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object ChannelCache {
    var channels: List<TvChannel> = emptyList()
}

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isAndroidTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        requestedOrientation = if (isAndroidTv) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            binding.tvSplashStatus.text = "Mengambil playlist TV Nasional Indonesia..."
            val loaded = runCatching { PlaylistRepository.loadAllChannels(this@SplashActivity) }
                .getOrElse { emptyList() }
            ChannelCache.channels = loaded

            binding.tvSplashStatus.text = if (loaded.isNotEmpty()) {
                "${loaded.size} channel siap diputar!"
            } else {
                "Playlist belum tersedia. Periksa koneksi internet."
            }
            delay(350)
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            finish()
        }
    }
}
