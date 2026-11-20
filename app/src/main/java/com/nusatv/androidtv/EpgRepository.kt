package com.nusatv.androidtv

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** XMLTV schedules from multiple feeds. Failed feeds are skipped independently. */
data class EpgSchedule(
    val current: String?,
    val next: String?
)

object EpgRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val feedUrls = listOf(
        "https://iptv-epg.org/files/epg-id.xml",
        "https://raw.githubusercontent.com/apistech/project/refs/heads/main/epgs/guide.xml",
        DefaultSources.XMLTV_URL
    ).distinct()

    suspend fun loadSchedules(): Map<String, EpgSchedule> = withContext(Dispatchers.IO) {
        val merged = HashMap<String, EpgSchedule>()
        var successfulFeeds = 0
        for (url in feedUrls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0")
                    .header("Referer", "https://www.rctiplus.com/")
                    .header("Origin", "https://www.rctiplus.com")
                    .header("Accept", "application/xml, text/xml, */*")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body ?: return@use
                    val parsed = parseXmltv(body.charStream())
                    if (parsed.isNotEmpty()) {
                        successfulFeeds++
                        parsed.forEach { (id, schedule) ->
                            val old = merged[id]
                            merged[id] = EpgSchedule(
                                current = old?.current ?: schedule.current,
                                next = old?.next ?: schedule.next
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                // One unavailable EPG provider must not block the remaining feeds.
            }
        }
        if (successfulFeeds == 0) error("Semua sumber XMLTV gagal dimuat")
        merged
    }

    private fun parseXmltv(reader: java.io.Reader): Map<String, EpgSchedule> {
        val parser = Xml.newPullParser()
        parser.setInput(reader)
        val now = System.currentTimeMillis()
        data class MutableSchedule(var current: Pair<Long, String>? = null, var next: Pair<Long, String>? = null)
        val schedules = HashMap<String, MutableSchedule>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("programme", true)) {
                val channelId = parser.getAttributeValue(null, "channel").orEmpty()
                val start = parseXmltvDate(parser.getAttributeValue(null, "start"))
                val stop = parseXmltvDate(parser.getAttributeValue(null, "stop"))
                var title = ""
                var depth = 1
                while (depth > 0) {
                    when (parser.next()) {
                        XmlPullParser.START_TAG -> {
                            depth++
                            if (depth == 2 && parser.name.equals("title", true) && title.isBlank()) {
                                title = parser.nextText().trim()
                                depth--
                            }
                        }
                        XmlPullParser.END_TAG -> depth--
                        XmlPullParser.END_DOCUMENT -> depth = 0
                    }
                }
                if (channelId.isNotBlank() && title.isNotBlank() && start > 0L && stop > start) {
                    val item = schedules.getOrPut(channelId) { MutableSchedule() }
                    when {
                        now >= start && now < stop -> {
                            if (item.current == null || start > item.current!!.first) item.current = start to title
                        }
                        start > now -> {
                            if (item.next == null || start < item.next!!.first) item.next = start to title
                        }
                    }
                }
            }
            event = parser.next()
        }
        return schedules.mapValues { (_, value) ->
            EpgSchedule(value.current?.second, value.next?.second)
        }
    }

    private fun parseXmltvDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val value = raw.trim()
        val formats = listOf("yyyyMMddHHmmss Z", "yyyyMMddHHmm Z", "yyyyMMddHHmmss")
        for (pattern in formats) {
            try {
                val format = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                return format.parse(value)?.time ?: continue
            } catch (_: Exception) { }
        }
        return 0L
    }
}
