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

/** Minimal in-memory XMLTV schedule: retain only current and next title per channel. */
data class EpgSchedule(
    val current: String?,
    val next: String?
)

object EpgRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
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
                                    depth-- // nextText consumes the matching title end tag
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
            schedules.mapValues { (_, value) ->
                EpgSchedule(value.current?.second, value.next?.second)
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
