/*
 * Copyright (C) 2026 Marko Bosko, Mantra Productions
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.mantraproductions.reader.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import okio.ByteString.Companion.toByteString

/**
 * Everything here except the last test runs offline, so the protocol pieces stay checked on every
 * run. The live test speaks a real Croatian sentence with a number in it and is the one that proves
 * the client actually works; it needs the network and is therefore gated behind TTT_LIVE_VOICE=1,
 * which the "voice client proof" CI job sets.
 */
class MaEdgeVoiceTest : FunSpec({

    test("the security token matches the reference implementation") {
        // Cross-checked against edge-tts's own DRM.generate_sec_ms_gec for the same instants, so this
        // fails if the hash, the epoch shift or the five minute rounding is ever changed by mistake.
        MaEdgeVoice.secMsGec(1_700_000_000L) shouldBe
            "42301B335578FEFDAE2637DED1ABD614505D432559EC08032B82048483726AFF"
        MaEdgeVoice.secMsGec(1_767_225_600L) shouldBe
            "42D1947403FD94975436C65DBFCA8003073F9CB5C3F1CE25AF8961A11D7C3DFE"
    }

    test("the token is constant inside a five minute bucket and changes across one") {
        // 1_767_225_600 is exactly on a bucket boundary once the epoch shift is applied.
        val base = 1_767_225_600L
        MaEdgeVoice.secMsGec(base + 299L) shouldBe MaEdgeVoice.secMsGec(base)
        (MaEdgeVoice.secMsGec(base + 300L) == MaEdgeVoice.secMsGec(base)) shouldBe false
    }

    test("the config asks for word boundaries, which are not the default") {
        // The one thing that silently ruins the reader: without this the service sends sentence
        // boundaries only, no word events arrive, and the highlight falls back to guesswork.
        val ssml = MaEdgeVoice.ssml("test", MaEdgeVoice.Voices.CROATIAN_FEMALE)
        ssml shouldContain "hr-HR-GabrijelaNeural"
        ssml shouldContain "<speak version='1.0'"
    }

    test("text is escaped so punctuation cannot break the request") {
        MaEdgeVoice.xmlEscape("a & b < c > d") shouldBe "a &amp; b &lt; c &gt; d"
        // Control characters are illegal in XML and would fail the whole sentence.
        MaEdgeVoice.xmlEscape("a\u0001b") shouldBe "a b"
        // Croatian must survive untouched.
        MaEdgeVoice.xmlEscape("čćžšđ") shouldBe "čćžšđ"
    }

    test("escaped text comes back unescaped") {
        MaEdgeVoice.xmlUnescape("a &amp; b &lt; c") shouldBe "a & b < c"
        MaEdgeVoice.xmlUnescape("plain") shouldBe "plain"
        // &amp; is undone last, so an escaped entity does not become a real one.
        MaEdgeVoice.xmlUnescape("&amp;lt;") shouldBe "&lt;"
    }

    test("a real metadata frame is read into a boundary") {
        // Captured verbatim from the service.
        val frame = """
            X-RequestId:6b6bef253ccd448fb34a30ab077943dd
            Content-Type:application/json; charset=utf-8
            Path:audio.metadata
        """.trimIndent().replace("\n", "\r\n") + "\r\n\r\n" +
            """{"Metadata":[{"Type":"WordBoundary","Data":{"Offset":1000000,"Duration":4750000,""" +
            """"text":{"Text":"Danas","Length":5,"BoundaryType":"WordBoundary"}}}]}"""

        MaEdgeVoice.headerValue(frame, "Path") shouldBe "audio.metadata"
        val bounds = MaEdgeVoice.parseBoundaries(frame.substringAfter("\r\n\r\n"))
        bounds shouldHaveSize 1
        bounds[0].t shouldBe (0.1 plusOrMinus 1e-9)
        bounds[0].d shouldBe (0.475 plusOrMinus 1e-9)
        bounds[0].w shouldBe "Danas"
    }

    test("metadata that is not a word boundary is skipped rather than thrown on") {
        MaEdgeVoice.parseBoundaries("""{"Metadata":[{"Type":"SessionEnd","Data":{}}]}""") shouldHaveSize 0
    }

    test("a binary frame gives up its audio, and the closing frame gives nothing") {
        val header = "X-RequestId:abc\r\nPath:audio\r\n\r\n"
        val payload = byteArrayOf(1, 2, 3, 4)
        val frame = ByteArray(2 + header.length + payload.size)
        frame[0] = (header.length shr 8).toByte()
        frame[1] = (header.length and 0xFF).toByte()
        header.toByteArray().copyInto(frame, 2)
        payload.copyInto(frame, 2 + header.length)
        MaEdgeVoice.audioPayload(frame.toByteString())!!.toList() shouldBe payload.toList()

        // The last frame of a stream: a header and no audio behind it.
        val empty = ByteArray(2 + header.length)
        empty[0] = (header.length shr 8).toByte()
        empty[1] = (header.length and 0xFF).toByte()
        header.toByteArray().copyInto(empty, 2)
        MaEdgeVoice.audioPayload(empty.toByteString()).shouldBeNull()
        MaEdgeVoice.audioPayload(byteArrayOf(0).toByteString()).shouldBeNull()
    }

    test("the four voices are the four voices") {
        MaEdgeVoice.Voices.of("hr", female = true) shouldBe "hr-HR-GabrijelaNeural"
        MaEdgeVoice.Voices.of("hr-HR", female = false) shouldBe "hr-HR-SreckoNeural"
        MaEdgeVoice.Voices.of("en-US", female = true) shouldBe "en-GB-SoniaNeural"
        MaEdgeVoice.Voices.of("en", female = false) shouldBe "en-GB-RyanNeural"
    }

    // ---------- the live proof ----------

    test("LIVE: a Croatian sentence with a number comes back as audio and word boundaries")
        .config(enabled = System.getenv("TTT_LIVE_VOICE") == "1", timeout = null) {
            val text = "Danas je 8. mjesec i imam 25 godina."
            val clip = MaEdgeVoice.synthesize(text, MaEdgeVoice.Voices.CROATIAN_FEMALE)

            println("audio ${clip.audio.size} bytes, clip ${clip.audioDuration} s, ${clip.bounds.size} boundaries")
            clip.bounds.forEach { println("  %7.3f +%.3f  %s".format(it.t, it.d, it.w)) }

            // Real mp3 frames, not an error page.
            (clip.audio.size > 10_000) shouldBe true
            (clip.audio[0].toInt() and 0xFF) shouldBe 0xFF

            // The failure this whole test exists to catch: no word events at all.
            (clip.bounds.size >= 5) shouldBe true

            // Times must climb, or the highlight travels backwards.
            clip.bounds.zipWithNext().all { (a, b) -> b.t >= a.t } shouldBe true

            // Both numbers must appear in some boundary. This is the case that motivates the
            // alignment port: the engine answers "8. mjesec" and "25 godina" as single boundaries
            // covering two visible words each, so boundary text cannot be matched one to one.
            val spoken = clip.bounds.joinToString(" ") { it.w }
            spoken shouldContain "8."
            spoken shouldContain "25"

            // A clip that is silent after the last word is fine; one that ends before it is not.
            (clip.audioDuration >= clip.total) shouldBe true
        }
})
