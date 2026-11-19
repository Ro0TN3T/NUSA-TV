package com.nusatv.androidtv

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class EpgProgram(
    val channelId: String,
    val title: String,
    val startMillis: Long,
    val stopMillis: Long
)

data class EpgSchedule(
    val current: String?,
    val next: String?
)

object EpgRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun loadSchedules(): Map<String, EpgSchedule> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(DefaultSources.XMLTV_URL)
            .header("User-Agent", "Mozilla/5.0 (Android; NusaTV EPG)")
            .header("Accept", "application/xml, text/xml, */*")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("XMLTV HTTP ${response.code}")
            val body = response.body ?: error("XMLTV kosong")
            val parser = Xml.newPullParser()
            parser.setInput(body.charStream())
            val programs = mutableMapOf<String, MutableList<EpgProgram>>()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name.equals("programme", true)) {
                    val channel = parser.getAttributeValue(null, "channel").orEmpty()
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
                            XmlPullParser.END_DOCUMENT -> break
                        }
                    }
                    if (channel.isNotBlank() && title.isNotBlank() && start > 0L && stop > start) {
                        programs.getOrPut(channel) { mutableListOf() }
                            .add(EpgProgram(channel, title, start, stop))
                    }
                }
                event = parser.next()
            }
            val now = System.currentTimeMillis()
            programs.mapValues { (_, entries) ->
                val ordered = entries.sortedBy { it.startMillis }
                val current = ordered.firstOrNull { now >= it.startMillis && now < it.stopMillis }
                val next = ordered.firstOrNull { it.startMillis > now }
                EpgSchedule(current?.title, next?.title)
            }
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
            } catch (_: Exception) {
                // Try the next common XMLTV timestamp format.
            }
        }
        return 0L
    }
}
