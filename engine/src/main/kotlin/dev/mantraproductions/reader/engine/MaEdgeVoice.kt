/*
 * Copyright (C) 2026 Marko Bosko, Mantra Productions
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.mantraproductions.reader.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Speaks one unit of text in a Microsoft Edge neural voice and hands back the mp3 together with the
 * word boundaries needed to light each word as it is spoken.
 *
 * MA Reader does this with edge-tts, which is Python and cannot run inside an input method. But
 * edge-tts is only a *client*: it opens a WebSocket to Microsoft's readaloud endpoint, sends SSML,
 * and reads back binary audio frames interleaved with JSON metadata frames. That protocol is what
 * is reimplemented here on OkHttp, following the WebSocket pattern in Talk to Type's
 * `RealtimeClient`.
 *
 * Knows nothing about either app that calls it: no keyboard, no view mode, no theme. Free of every
 * Android API too, so it runs as a plain JVM unit test, which is the only way this protocol gets
 * verified before it reaches a phone. See `MaEdgeVoiceTest`.
 */
object MaEdgeVoice {

    /** The four voices, and no more. Two languages, female and male in each. */
    object Voices {
        const val ENGLISH_FEMALE = "en-GB-SoniaNeural"
        const val ENGLISH_MALE = "en-GB-RyanNeural"
        const val CROATIAN_FEMALE = "hr-HR-GabrijelaNeural"
        const val CROATIAN_MALE = "hr-HR-SreckoNeural"

        /** [languageTag] is the caller's language, which picks the pair; the reader picks the sex. */
        fun of(languageTag: String, female: Boolean): String =
            if (languageTag.startsWith("hr", ignoreCase = true)) {
                if (female) CROATIAN_FEMALE else CROATIAN_MALE
            } else {
                if (female) ENGLISH_FEMALE else ENGLISH_MALE
            }
    }

    // The endpoint, the client token and the extension origin are what the Edge browser itself sends.
    // Every one of these is checked: a stale Chromium version or the wrong extension id is rejected
    // with HTTP 403 before a single frame is exchanged, which is how this looks when it breaks.
    private const val WSS_URL =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    private const val CHROMIUM_MAJOR_VERSION = "143"
    private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR_VERSION.0.0.0"

    /** The output format asked for, and the only one the timing arithmetic below is valid for. */
    private const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"
    private const val MP3_BITRATE_BPS = 48_000

    /** Offsets and durations arrive in 100 ns ticks. */
    private const val TICKS_PER_SECOND = 10_000_000.0

    /** Seconds between the Windows file time epoch (1601) and the Unix epoch (1970). */
    private const val WIN_EPOCH_SECONDS = 11_644_473_600L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .fastFallback(true)
            .readTimeout(0, TimeUnit.SECONDS) // the clip streams in over many frames
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    /**
     * One word boundary exactly as the engine reports it: [t] start and [d] duration in seconds,
     * [w] the text the engine considers it just spoke.
     *
     * [w] is not reliably one visible word. Asked to read "Danas je 8. mjesec", the engine answers
     * with a single boundary whose text is "8. mjesec": it merged a number and the word after it.
     * Mapping these onto the visible characters is `align_tokens`, which is the next piece of work
     * and is where the highlight quality actually lives.
     */
    data class Boundary(val t: Double, val d: Double, val w: String)

    /**
     * A synthesised unit: the [audio] mp3 bytes, the [bounds] in order, and two different ideas of
     * how long the clip is.
     *
     * [total] is the end of the last timed word, which is what MA Reader anchors the highlight
     * sweep to. [audioDuration] is derived from the byte count, exact for a constant bitrate
     * stream, and is the length actually heard: it includes the tail of silence after the last
     * word, so it is always the larger of the two.
     */
    data class Clip(
        val audio: ByteArray,
        val bounds: List<Boundary>,
        val total: Double,
        val audioDuration: Double,
    ) {
        // Generated equals/hashCode would compare the audio array by identity, which is a trap in a
        // data class. Compare by content so two identical clips are equal.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Clip) return false
            return audio.contentEquals(other.audio) && bounds == other.bounds &&
                total == other.total && audioDuration == other.audioDuration
        }

        override fun hashCode(): Int {
            var result = audio.contentHashCode()
            result = 31 * result + bounds.hashCode()
            result = 31 * result + total.hashCode()
            result = 31 * result + audioDuration.hashCode()
            return result
        }
    }

    class VoiceException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Speaks [text] in [voice] and returns the clip. Blocking, so call it off the main thread; one
     * call is one sentence, which is the unit the reader caches and plays.
     *
     * A phone whose clock is wrong is rejected with 403, because the security token is a hash of the
     * current time. That case is not an error: the server's own `Date` header says what the time
     * really is, so the token is rebuilt against it and the call retried once. This is worth having
     * on a device where nothing guarantees the clock, and it is why [clockSkewSeconds] persists for
     * the rest of the session.
     */
    fun synthesize(text: String, voice: String, timeoutSeconds: Long = 30): Clip {
        require(text.isNotBlank()) { "nothing to speak" }
        return try {
            attempt(text, voice, timeoutSeconds)
        } catch (e: VoiceException) {
            val skew = pendingSkewSeconds ?: throw e
            pendingSkewSeconds = null
            clockSkewSeconds = skew
            attempt(text, voice, timeoutSeconds)
        }
    }

    /** Clock correction learned from a rejected call, kept for the rest of the session. */
    @Volatile
    var clockSkewSeconds: Long = 0L
        internal set

    @Volatile
    private var pendingSkewSeconds: Long? = null

    private fun attempt(text: String, voice: String, timeoutSeconds: Long): Clip {
        val audio = java.io.ByteArrayOutputStream()
        val bounds = mutableListOf<Boundary>()
        val failure = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(1)
        val requestId = connectId()

        val url = "$WSS_URL?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
            "&ConnectionId=${connectId()}" +
            "&Sec-MS-GEC=${secMsGec()}" +
            "&Sec-MS-GEC-Version=1-$CHROMIUM_FULL_VERSION"

        // Sec-WebSocket-Version is deliberately NOT set here. OkHttp writes it itself, and sending it
        // again makes a duplicate header that the endpoint answers with HTTP 400. It cost a probe.
        val request = Request.Builder()
            .url(url)
            .header("Origin", ORIGIN)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "gzip, deflate, br, zstd")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Cookie", "muid=${muid()};")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Word boundaries are the whole point of the reader, and they are NOT the default.
                // edge-tts 7.x flipped its default to SentenceBoundary, which produces no word events
                // at all and silently drops MA Reader onto spreading words evenly across the clip.
                // Ask for them explicitly, every time. The trailing CRLF after the JSON is part of
                // the message the service expects.
                webSocket.send(
                    "X-Timestamp:${timestamp()}\r\n" +
                        "Content-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
                        "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"}," +
                        "\"outputFormat\":\"$OUTPUT_FORMAT\"}}}}\r\n",
                )
                // The trailing Z on this timestamp is not a typo. It is a bug in Edge itself and the
                // service expects it.
                webSocket.send(
                    "X-RequestId:$requestId\r\n" +
                        "Content-Type:application/ssml+xml\r\n" +
                        "X-Timestamp:${timestamp()}Z\r\n" +
                        "Path:ssml\r\n\r\n" +
                        ssml(text, voice),
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (headerValue(text, "Path")) {
                    "audio.metadata" -> {
                        val body = text.substringAfter("\r\n\r\n", "")
                        runCatching { parseBoundaries(body) }
                            .onSuccess { bounds.addAll(it) }
                    }
                    "turn.end" -> {
                        // The service closes on its own after this, but waiting for that costs a
                        // second per sentence and there is nothing left to read.
                        runCatching { webSocket.close(1000, null) }
                        done.countDown()
                    }
                    // turn.start and response carry no payload we need.
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                audioPayload(bytes)?.let { audio.write(it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code == 403) {
                    // The token is a hash of the current time, so a wrong clock reads as forbidden.
                    // The server's Date header is the truth; remember the difference and let
                    // synthesize() retry once against it.
                    pendingSkewSeconds = response.headers.getInstant("Date")?.let {
                        it.epochSecond - System.currentTimeMillis() / 1000L
                    }
                }
                failure.set(
                    VoiceException("voice endpoint refused the call (http=${response?.code}): ${t.message}", t),
                )
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.countDown()
            }
        }

        val ws = wsClient.newWebSocket(request, listener)
        try {
            if (!done.await(timeoutSeconds, TimeUnit.SECONDS)) {
                throw VoiceException("voice timed out after $timeoutSeconds s")
            }
        } finally {
            runCatching { ws.cancel() }
        }
        failure.get()?.let { throw it as? VoiceException ?: VoiceException("voice failed: ${it.message}", it) }

        val bytes = audio.toByteArray()
        if (bytes.isEmpty()) throw VoiceException("voice returned no audio")

        val total = bounds.maxOfOrNull { it.t + it.d } ?: 0.0
        // Exact for a constant bitrate stream: bytes * 8 / bits per second.
        val audioDuration = bytes.size.toDouble() * 8.0 / MP3_BITRATE_BPS
        return Clip(audio = bytes, bounds = bounds, total = total, audioDuration = audioDuration)
    }

    // ---------- the pieces, kept separate so they can be tested without a network ----------

    /**
     * The `Sec-MS-GEC` security token: SHA-256 of the current Windows file time rounded down to five
     * minutes, concatenated with the client token, uppercase hex. Rounding to five minutes is what
     * lets the server recompute the same value.
     */
    internal fun secMsGec(nowSeconds: Long = System.currentTimeMillis() / 1000L + clockSkewSeconds): String {
        var ticks = nowSeconds + WIN_EPOCH_SECONDS
        ticks -= ticks % 300
        ticks *= 10_000_000L // to 100 ns intervals; a Long holds this until the year 30000
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$ticks$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII))
        val hex = StringBuilder(64)
        for (b in digest) hex.append("%02X".format(b))
        return hex.toString()
    }

    /** The SSML the service reads. Pitch, rate and volume stay neutral; speed is the player's job. */
    internal fun ssml(text: String, voice: String): String =
        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='$voice'>" +
            "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>" +
            xmlEscape(text) +
            "</prosody></voice></speak>"

    /**
     * Reads the boundaries out of one metadata frame. Anything that is not a word boundary is
     * ignored rather than treated as an error: the service also sends session markers, and a reader
     * that threw on one would stop mid sentence for no reason.
     */
    internal fun parseBoundaries(body: String): List<Boundary> {
        val meta = json.parseToJsonElement(body).jsonObject["Metadata"]?.jsonArray ?: return emptyList()
        return meta.mapNotNull { entry ->
            val obj = entry.jsonObject
            if (obj["Type"]?.jsonPrimitive?.content != "WordBoundary") return@mapNotNull null
            val data = obj["Data"]?.jsonObject ?: return@mapNotNull null
            val offset = data["Offset"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val duration = data["Duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val word = data["text"]?.jsonObject?.get("Text")?.jsonPrimitive?.content ?: return@mapNotNull null
            Boundary(t = offset / TICKS_PER_SECOND, d = duration / TICKS_PER_SECOND, w = xmlUnescape(word))
        }
    }

    /**
     * The audio out of one binary frame: two big-endian bytes of header length, the header, then the
     * mp3. Returns null when there is nothing to write, which includes the frame that ends the
     * stream: it carries a header and no audio at all, and treating that as an error would fail
     * every sentence at its last frame.
     */
    internal fun audioPayload(bytes: ByteString): ByteArray? {
        if (bytes.size < 2) return null
        val headerLength = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        val start = 2 + headerLength
        if (start >= bytes.size) return null
        return bytes.substring(start, bytes.size).toByteArray()
    }

    /** The value of one header in a text frame, whose headers are CRLF separated `Name:value` lines. */
    internal fun headerValue(frame: String, name: String): String? {
        val head = frame.substringBefore("\r\n\r\n")
        for (line in head.split("\r\n")) {
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).equals(name, ignoreCase = true)) {
                return line.substring(colon + 1).trim()
            }
        }
        return null
    }

    internal fun xmlEscape(text: String): String = buildString(text.length + 16) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            // Control characters are not legal in XML and the service rejects the whole request for
            // one of them, so they become spaces rather than a failed sentence.
            in '\u0000'..'\u0008', '\u000B', '\u000C', in '\u000E'..'\u001F' -> append(' ')
            else -> append(c)
        }
    }

    internal fun xmlUnescape(text: String): String =
        if (text.indexOf('&') < 0) {
            text
        } else {
            text.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&") // last, so "&amp;lt;" does not become "<"
        }

    private fun connectId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun muid(): String = (connectId()).uppercase(Locale.ROOT)

    /** The JavaScript style date the service expects, always in UTC. */
    private fun timestamp(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date(System.currentTimeMillis() + clockSkewSeconds * 1000L))
    }
}
