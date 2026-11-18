package com.nusatv.androidtv

import android.content.Intent
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
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            binding.tvSplashStatus.text = "Mengambil playlist TV Nasional Indonesia..."
            val loaded = PlaylistRepository.loadAllChannels()
            ChannelCache.channels = loaded

            binding.tvSplashStatus.text = "${loaded.size} channel siap diputar!"
            delay(500)

            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            finish()
        }
    }
}
