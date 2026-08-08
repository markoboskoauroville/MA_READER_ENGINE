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

import java.text.Normalizer

/**
 * Maps the speech engine's word boundaries onto exact character ranges in the visible text.
 *
 * This is the part that decides whether the highlight sits on the word being spoken, which is the
 * whole product rather than a flourish. It is a port of `align_tokens` from MA Reader v26, branch
 * for branch and deliberately not simplified: every branch in there exists because something went
 * wrong once, and the recovery cases do not show up until a paragraph has a number in it.
 *
 * The problem it solves: boundary text does not correspond to visible words one to one. Asked to
 * read "Danas je 8. mjesec i imam 25 godina." the service answers with six boundaries for eight
 * visible words, because it merged each number with the word after it. Elsewhere it expands a
 * number into several boundaries, spells an acronym one letter at a time, reads a symbol aloud, or
 * emits a boundary matching nothing visible at all.
 *
 * The rule that matters most: when matching fails, this still returns a left to right spread
 * proportional to each word's length, rather than dropping every word at zero, which used to pin the
 * highlight to the last word for a whole sentence.
 */
object MaAlign {

    /**
     * One visible word run and when the highlight should be on it.
     *
     * [s] and [e] are character offsets into the sentence, [t] is when the highlight arrives and
     * [d] is when it leaves. Names kept as they are in MA Reader v26 so the two can be read side by
     * side while the port is still young.
     */
    data class Token(val s: Int, val e: Int, val t: Double, val d: Double)

    // Python's \S is Unicode aware; Java's is ASCII only unless asked. Without (?U) a non-breaking
    // space inside a pasted article would be treated as part of a word and the two implementations
    // would disagree on where words even are.
    private val tokenRegex = Regex("(?U)\\S+")

    /**
     * Letters and digits only, lowercased, accents folded. This is what lets a spoken word be
     * matched to visible text when the voice has normalised punctuation or case.
     */
    internal fun norm(s: String): String {
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFKD)
        val out = StringBuilder(decomposed.length)
        for (c in decomposed) {
            // Combining marks are dropped, which is what folds an accent onto its base letter. The
            // Croatian đ is its own letter rather than d plus a mark, so it survives NFKD intact and
            // is matched as itself.
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            if (isAlnum(c)) out.append(c.lowercase())
        }
        return out.toString()
    }

    // Python's str.isalnum() is wider than Kotlin's isLetterOrDigit(): it also counts numeric
    // characters like ½ and Ⅷ, which turn up in real text often enough to matter.
    private fun isAlnum(c: Char): Boolean = c.isLetterOrDigit() ||
        Character.getType(c) == Character.OTHER_NUMBER.toInt() ||
        Character.getType(c) == Character.LETTER_NUMBER.toInt()

    /**
     * For every visible word run in [sentence], where it starts and ends in characters and when the
     * highlight should arrive and leave. [bounds] are the engine's word boundaries; [total] is the
     * clip length in seconds when it is known, which anchors the tail so the last word releases at
     * the right moment rather than hanging.
     *
     * The times are always non decreasing, always span the clip, and never collapse onto one word.
     */
    fun alignTokens(sentence: String, bounds: List<MaEdgeVoice.Boundary>, total: Double? = null): List<Token> {
        val tokens = tokenRegex.findAll(sentence).map { it.range.first to it.range.last + 1 }.toList()
        val n = tokens.size
        if (n == 0) return emptyList()

        // t and d stay null while they are unknown, because "not timed yet" and "timed at zero" are
        // different things and collapsing them is what used to put every word at the start.
        val startTimes = arrayOfNulls<Double>(n)
        val endTimes = arrayOfNulls<Double>(n)

        val bn = bounds.map { Triple(norm(it.w), it.t, it.d) }.filter { it.first.isNotEmpty() }
        val nB = bn.size
        var bi = 0

        for (ti in 0 until n) {
            val (a, b) = tokens[ti]
            val tok = norm(sentence.substring(a, b))
            if (tok.isEmpty()) continue // pure punctuation: interpolated later

            val startBi = bi
            var guard = 0
            var found = false
            // Skip a few stray boundaries to resync. Six is the reference's limit: enough to step
            // over something the voice said that is not on the page, not so many that it runs off
            // and matches a word much later in the sentence.
            while (bi < nB && guard < 6) {
                val bw = bn[bi].first
                if (tok.startsWith(bw) || bw.startsWith(tok) || bw == tok) {
                    found = true
                    break
                }
                bi++
                guard++
            }
            if (!found) {
                bi = startBi // consume nothing, leave this word to interpolation
                continue
            }

            startTimes[ti] = bn[bi].second
            var lastT = bn[bi].second
            var lastD = bn[bi].third
            // Consume the boundaries that build this word up, which is the acronym case: A, B, C
            // arriving separately for one visible ABC.
            var acc = ""
            while (bi < nB) {
                acc += bn[bi].first
                lastT = bn[bi].second
                lastD = bn[bi].third
                bi++
                if (acc == tok || !tok.startsWith(acc)) break
            }
            endTimes[ti] = lastT + lastD
        }

        val known = (0 until n).mapNotNull { i -> startTimes[i]?.let { i to it } }
        val widths = tokens.map { maxOf(1, it.second - it.first) }

        if (known.isEmpty()) {
            // Nothing matched. Spread proportionally across the clip so the highlight still travels
            // word by word instead of freezing on the last one.
            //
            // This returns straight out, without the tidying pass below, exactly as the reference
            // does. It matters: the spread already ends the last word at the end of the span, and
            // running the pass over it would instead cut that word to its own start plus 0.4 s when
            // no total is known, which is shorter than the span it was just given. Sending this
            // through the same ending as every other path looks tidier and is wrong.
            val span = if (total != null && total > 0.1) total else 0.32 * n
            var acc = 0.0
            val wsum = widths.sum().toDouble()
            val out = ArrayList<Token>(n)
            for (i in 0 until n) {
                val start = span * (acc / wsum)
                acc += widths[i]
                out.add(Token(s = tokens[i].first, e = tokens[i].second, t = start, d = span * (acc / wsum)))
            }
            return out
        }

        // Before the first known time, hold steady rather than inventing motion.
        val (fi, ft) = known.first()
        for (i in 0 until fi) startTimes[i] = ft

        // Between known anchors, weight by word width so a long word gets proportionally more time
        // than a short one.
        for (k in 0 until known.size - 1) {
            val (i0, t0) = known[k]
            val (i1, t1) = known[k + 1]
            if (i1 - i0 <= 1) continue
            val seg = widths.subList(i0 + 1, i1)
            val ws = seg.sum().toDouble().let { if (it == 0.0) 1.0 else it }
            var acc = 0.0
            for ((idx, j) in (i0 + 1 until i1).withIndex()) {
                acc += seg[idx]
                startTimes[j] = t0 + (t1 - t0) * (acc / ws) - (t1 - t0) * (seg[idx] / ws)
            }
        }

        // After the last known time, spread toward the end of the clip.
        val (li, lt) = known.last()
        val tailEnd = if (total != null && total > lt + 0.05) total else lt + 0.45 * (n - li)
        val rest = (li + 1 until n).toList()
        if (rest.isNotEmpty()) {
            val seg = rest.map { widths[it] }
            val ws = seg.sum().toDouble().let { if (it == 0.0) 1.0 else it }
            var acc = 0.0
            for ((idx, j) in rest.withIndex()) {
                startTimes[j] = lt + (tailEnd - lt) * (acc / ws)
                acc += seg[idx]
            }
        }

        return finish(tokens, startTimes, endTimes, n, total)
    }

    /**
     * Forces the two properties the highlight depends on: start times that never go backwards, and
     * an end time for every word so none of them is ever zero length and skipped.
     */
    private fun finish(
        tokens: List<Pair<Int, Int>>,
        startTimes: Array<Double?>,
        endTimes: Array<Double?>,
        n: Int,
        total: Double?,
    ): List<Token> {
        var prev = 0.0
        for (i in 0 until n) {
            val t = startTimes[i]
            if (t == null || t < prev) startTimes[i] = prev
            prev = startTimes[i]!!
        }
        for (i in 0 until n) {
            val t = startTimes[i]!!
            // A word ends when the next begins; the last one ends at the clip end.
            val nxt = if (i + 1 < n) startTimes[i + 1]!! else {
                if (total != null && total > t) total else t + 0.4
            }
            val d = endTimes[i]
            if (d == null || d <= t || d > nxt) endTimes[i] = nxt
            if (endTimes[i]!! <= t) endTimes[i] = nxt
        }
        return (0 until n).map { i ->
            Token(s = tokens[i].first, e = tokens[i].second, t = startTimes[i]!!, d = endTimes[i]!!)
        }
    }
}
