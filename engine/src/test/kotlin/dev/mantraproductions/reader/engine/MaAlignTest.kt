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

/**
 * The port is checked against the thing it was ported from, not against my own idea of what it
 * should do. `align_vectors.json` was produced by running MA Reader v26's own `align_tokens` in
 * Python over every case below, so a difference here means the Kotlin has drifted from a year of
 * work rather than that somebody's expectation was wrong.
 *
 * Seven of the cases carry real boundaries captured from the live speech service, in both
 * languages, chosen for the things that break matching: numbers, times, dates, currency, acronyms
 * and Croatian diacritics. The rest are cases the service will not produce on demand but a reader
 * will meet, and each one exercises a specific branch that exists because something went wrong once.
 *
 * Regenerate with `mkvectors.py` if the reference itself ever changes, never by hand.
 */
class MaAlignTest : FunSpec({

    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    val vectors = json.parseToJsonElement(
        MaAlignTest::class.java.getResourceAsStream("/align_vectors.json")!!
            .readBytes().decodeToString(),
    ).jsonArray

    test("the vectors are actually loaded") {
        // A test that silently iterates an empty list is worse than no test.
        (vectors.size >= 20) shouldBe true
    }

    vectors.forEach { entry ->
        val case = entry.jsonObject
        val name = case["name"]!!.jsonPrimitive.content

        test("matches the Python reference: $name") {
            val sentence = case["sentence"]!!.jsonPrimitive.content
            val total = case["total"]?.jsonPrimitive?.doubleOrNull
            val bounds = case["bounds"]!!.jsonArray.map {
                val b = it.jsonObject
                MaEdgeVoice.Boundary(
                    t = b["t"]!!.jsonPrimitive.double,
                    d = b["d"]!!.jsonPrimitive.double,
                    w = b["w"]!!.jsonPrimitive.content,
                )
            }
            val expected = case["expected"]!!.jsonArray.map { it.jsonObject }

            val actual = MaAlign.alignTokens(sentence, bounds, total)

            actual.size shouldBe expected.size
            actual.forEachIndexed { i, tok ->
                val exp = expected[i]
                // Character offsets must be exact: they are what gets highlighted.
                tok.s shouldBe exp["s"]!!.jsonPrimitive.int
                tok.e shouldBe exp["e"]!!.jsonPrimitive.int
                // Times are floating point in both languages, so compare to well under the
                // millisecond that any of this is measured in.
                tok.t shouldBe (exp["t"]!!.jsonPrimitive.double plusOrMinus 1e-9)
                tok.d shouldBe (exp["d"]!!.jsonPrimitive.double plusOrMinus 1e-9)
            }
        }
    }

    // The properties the highlight depends on, asserted directly rather than only through the
    // vectors, so they stay guarded even if the vectors are ever regenerated wrongly.

    test("times never go backwards and never leave before they arrive, on every case") {
        vectors.forEach { entry ->
            val case = entry.jsonObject
            val sentence = case["sentence"]!!.jsonPrimitive.content
            val total = case["total"]?.jsonPrimitive?.doubleOrNull
            val bounds = case["bounds"]!!.jsonArray.map {
                val b = it.jsonObject
                MaEdgeVoice.Boundary(b["t"]!!.jsonPrimitive.double, b["d"]!!.jsonPrimitive.double, b["w"]!!.jsonPrimitive.content)
            }
            val toks = MaAlign.alignTokens(sentence, bounds, total)
            toks.zipWithNext().forEach { (a, b) ->
                withClue(sentence) { (b.t >= a.t) shouldBe true }
            }
            toks.forEach { withClue(sentence) { (it.d >= it.t) shouldBe true } }
        }
    }

    test("the highlight never collapses onto one word when nothing matches") {
        // The failure this rule exists to prevent: every word at zero, so the sweep lands on the
        // last word and stays there for the whole sentence.
        val toks = MaAlign.alignTokens(
            "Alpha beta gamma delta epsilon.",
            listOf(MaEdgeVoice.Boundary(0.1, 0.3, "nothing")),
            total = 3.0,
        )
        toks.size shouldBe 5
        toks.map { it.t }.distinct().size shouldBe 5
        (toks.last().t > toks.first().t) shouldBe true
    }

    test("character ranges point at the words they claim to") {
        val sentence = "Danas je 8. mjesec i imam 25 godina."
        val toks = MaAlign.alignTokens(sentence, emptyList(), 3.5)
        toks.map { sentence.substring(it.s, it.e) } shouldBe
            listOf("Danas", "je", "8.", "mjesec", "i", "imam", "25", "godina.")
    }

    test("accents fold and case is ignored when matching") {
        MaAlign.norm("ČITAM") shouldBe "citam"
        MaAlign.norm("Žito") shouldBe "zito"
        MaAlign.norm("Šuma") shouldBe "suma"
        // đ is its own letter rather than d plus a mark, so NFKD leaves it whole and it matches
        // itself rather than folding to d.
        MaAlign.norm("Đak") shouldBe "đak"
        MaAlign.norm("8. mjesec") shouldBe "8mjesec"
        MaAlign.norm("--,.") shouldBe ""
    }
})
