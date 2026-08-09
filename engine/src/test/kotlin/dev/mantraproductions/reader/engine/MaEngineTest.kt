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

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * The text and waveform ports checked against the Python they came from. Vectors in
 * `engine_vectors.json`, produced by `tools/mkengine.py` running MA Reader v26's own functions.
 *
 * The clips are synthesised rather than recorded, on purpose. A generated signal with known word
 * positions exercises every branch that matters, a fricative opening, a shouted word among quiet
 * ones, a long pause, a clip that starts on speech with no lead-in, and a continuous phrase where
 * the middle word has no onset of its own at all. One real recording would hit maybe two of those.
 */
class MaEngineTest : FunSpec({

    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    val vectors = json.parseToJsonElement(
        MaEngineTest::class.java.getResourceAsStream("/engine_vectors.json")!!
            .readBytes().decodeToString(),
    ).jsonObject

    val textCases = vectors["text"]!!.jsonArray
    val waveCases = vectors["waveform"]!!.jsonArray

    test("the vectors are actually loaded") {
        // A suite that silently iterates an empty list is worse than no suite.
        (textCases.size >= 15) shouldBe true
        (waveCases.size >= 5) shouldBe true
    }

    // ---------- text ----------

    textCases.forEachIndexed { i, entry ->
        val case = entry.jsonObject
        val input = case["input"]!!.jsonPrimitive.content

        test("cleanText matches the reference, case $i") {
            withClue(input.take(60)) {
                MaText.cleanText(input) shouldBe case["clean"]!!.jsonPrimitive.content
            }
        }

        test("splitUnits matches the reference, case $i") {
            val expected = case["units"]!!.jsonArray.map {
                it.jsonArray[0].jsonPrimitive.int to it.jsonArray[1].jsonPrimitive.int
            }
            val actual = MaText.splitUnits(input).map { it.first to it.last + 1 }
            withClue(input.take(60)) { actual shouldBe expected }
        }

        test("splitUnits on cleaned text matches the reference, case $i") {
            // The order the reader actually uses: clean first, then cut. Checked separately because
            // cleaning changes offsets, and a unit span that is right for the raw text and wrong for
            // the cleaned one would highlight the wrong words with no other symptom.
            val cleaned = MaText.cleanText(input)
            val expected = case["clean_units"]!!.jsonArray.map {
                it.jsonArray[0].jsonPrimitive.int to it.jsonArray[1].jsonPrimitive.int
            }
            val actual = MaText.splitUnits(cleaned).map { it.first to it.last + 1 }
            withClue(cleaned.take(60)) { actual shouldBe expected }
        }
    }

    test("no unit ever exceeds the cap, and units never overlap") {
        textCases.forEach { entry ->
            val cleaned = MaText.cleanText(entry.jsonObject["input"]!!.jsonPrimitive.content)
            val units = MaText.splitUnits(cleaned)
            units.forEach {
                withClue(cleaned.take(40)) { (it.last + 1 - it.first <= MaText.UNIT_CAP) shouldBe true }
            }
            units.zipWithNext().forEach { (a, b) ->
                withClue(cleaned.take(40)) { (b.first >= a.last + 1) shouldBe true }
            }
        }
    }

    // ---------- waveform ----------

    fun pcmOf(case: kotlinx.serialization.json.JsonObject): ShortArray {
        val bytes = Base64.getDecoder().decode(case["pcm_b64"]!!.jsonPrimitive.content)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(buf.remaining()).also { buf.get(it) }
    }

    waveCases.forEach { entry ->
        val case = entry.jsonObject
        val name = case["name"]!!.jsonPrimitive.content

        test("the envelope matches the reference: $name") {
            val e = MaWaveform.envelope(pcmOf(case))
            e.env.size shouldBe case["env_len"]!!.jsonPrimitive.int
            e.duration shouldBe (case["duration"]!!.jsonPrimitive.double plusOrMinus 1e-9)
            case["env_head"]!!.jsonArray.forEachIndexed { i, v ->
                e.env[i] shouldBe (v.jsonPrimitive.double plusOrMinus 1e-5)
            }
            case["hi_head"]!!.jsonArray.forEachIndexed { i, v ->
                e.hi[i] shouldBe (v.jsonPrimitive.double plusOrMinus 1e-5)
            }
        }

        test("speech span and rises match the reference: $name") {
            val e = MaWaveform.envelope(pcmOf(case))
            val (onset, last) = MaWaveform.speechSpan(e)
            onset!! shouldBe (case["onset"]!!.jsonPrimitive.double plusOrMinus 1e-9)
            last!! shouldBe (case["last"]!!.jsonPrimitive.double plusOrMinus 1e-9)

            val expectedRises = case["rises"]!!.jsonArray.map { it.jsonPrimitive.double }
            val rises = MaWaveform.risePoints(e)
            rises.size shouldBe expectedRises.size
            rises.forEachIndexed { i, r -> r shouldBe (expectedRises[i] plusOrMinus 1e-6) }
        }

        test("the silence map matches the reference: $name") {
            val e = MaWaveform.envelope(pcmOf(case))
            val (onset, last) = MaWaveform.speechSpan(e)
            val expected = case["silence"]!!.jsonArray.map {
                it.jsonArray[0].jsonPrimitive.double to it.jsonArray[1].jsonPrimitive.double
            }
            val actual = MaWaveform.silenceRuns(e, onset, last).map { it[0] to it[1] }
            actual.size shouldBe expected.size
            actual.forEachIndexed { i, (a, b) ->
                a shouldBe (expected[i].first plusOrMinus 1e-9)
                b shouldBe (expected[i].second plusOrMinus 1e-9)
            }
        }

        test("refine matches the reference: $name") {
            val tokens = case["tokens"]!!.jsonArray.map {
                val o = it.jsonObject
                MaAlign.Token(
                    s = o["s"]!!.jsonPrimitive.int,
                    e = o["e"]!!.jsonPrimitive.int,
                    t = o["t"]!!.jsonPrimitive.double,
                    d = o["d"]!!.jsonPrimitive.double,
                )
            }
            val result = MaWaveform.refine(pcmOf(case), tokens)
            result.changed shouldBe (case["changed"]!!.jsonPrimitive.content == "true")

            val expected = case["refined"]!!.jsonArray.map { it.jsonObject }
            result.tokens.size shouldBe expected.size
            result.tokens.forEachIndexed { i, tok ->
                withClue("$name word $i") {
                    tok.s shouldBe expected[i]["s"]!!.jsonPrimitive.int
                    tok.e shouldBe expected[i]["e"]!!.jsonPrimitive.int
                    tok.t shouldBe (expected[i]["t"]!!.jsonPrimitive.double plusOrMinus 1e-9)
                    tok.d shouldBe (expected[i]["d"]!!.jsonPrimitive.double plusOrMinus 1e-9)
                }
            }
        }

        test("refine leaves the highlight usable: $name") {
            // The properties the reader depends on, asserted directly rather than only through the
            // vectors, so they stay guarded even if the vectors are ever regenerated wrongly.
            val tokens = case["tokens"]!!.jsonArray.map {
                val o = it.jsonObject
                MaAlign.Token(o["s"]!!.jsonPrimitive.int, o["e"]!!.jsonPrimitive.int,
                    o["t"]!!.jsonPrimitive.double, o["d"]!!.jsonPrimitive.double)
            }
            val out = MaWaveform.refine(pcmOf(case), tokens).tokens
            out.zipWithNext().forEach { (a, b) ->
                withClue(name) { (b.t > a.t) shouldBe true }
            }
            out.forEach { withClue(name) { (it.d > it.t) shouldBe true } }
        }
    }

    test("refine refuses rather than guesses when there is nothing to measure") {
        // Silence, and a clip far too short. Both must return the tokens untouched: a highlight on
        // the engine's own timing is slightly late, one on a failed measurement is anywhere at all.
        val tokens = listOf(MaAlign.Token(0, 4, 0.1, 0.5), MaAlign.Token(5, 9, 0.6, 1.0))
        MaWaveform.refine(ShortArray(16_000), tokens).changed shouldBe false
        MaWaveform.refine(ShortArray(100), tokens).changed shouldBe false
        MaWaveform.refine(ShortArray(16_000), emptyList()).tokens shouldBe emptyList()
    }
})
