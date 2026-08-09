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

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Listens to the finished clip and moves every word onto where it is actually spoken.
 *
 * The speech engine's own word boundaries are good but systematically late, because they describe
 * what the synthesiser intended rather than what came out of the encoder. Measured against
 * hand-checked onsets the reference engine took a mean error of about 80 ms down to about 18 ms with
 * this pass, and around 150 ms on words that open with s, sh or f. That is the difference between a
 * highlight that rides the voice and one that visibly trails it.
 *
 * Ported from `_pcm_env`, `_levels`, `_speech_span`, `silence_runs`, `_rise_points`,
 * `_match_anchors` and `refine_tokens` in MA Reader v26, constants and all. The numbers here were
 * tuned against a real corpus; changing one because it looks arbitrary is how this stops working.
 *
 * Takes decoded PCM rather than a file, which is the whole reason it can live in this module: the
 * reference shells out to ffmpeg, an app cannot, and the maths does not care where the samples came
 * from. The caller decodes (MediaCodec on Android) and hands over mono 16-bit at [ENV_SR].
 */
object MaWaveform {

    /** Decode rate. Nyquist at 8 kHz, so fricatives are still visible. */
    const val ENV_SR = 16_000

    /** One envelope frame every 5 ms, each measuring a 20 ms window, so frames overlap. */
    private const val ENV_HOP_MS = 5
    private const val ENV_WIN_MS = 20

    /** Seconds per envelope frame. */
    const val W = ENV_HOP_MS / 1000.0

    private const val SIL_THR = 0.06
    private const val SIL_MIN_MS = 70
    private const val SIL_GUARD_MS = 18

    private const val MIN_PAUSE_MS = 90
    private const val BACKTRACK_MS = 220

    private const val ANCHOR_SKIP = 0.35
    private const val ANCHOR_CAP = 0.50

    /**
     * Two envelopes of the same clip and its length in seconds.
     *
     * [env] is broadband. [hi] is the same measurement on a pre-emphasised copy, which lifts
     * fricatives and plosive bursts far above the floor.
     */
    data class Envelope(val env: DoubleArray, val hi: DoubleArray, val duration: Double) {
        val usable: Boolean get() = env.size >= 4 && hi.size >= 4

        override fun equals(other: Any?): Boolean =
            other is Envelope && env.contentEquals(other.env) && hi.contentEquals(other.hi) &&
                duration == other.duration

        override fun hashCode(): Int =
            31 * (31 * env.contentHashCode() + hi.contentHashCode()) + duration.hashCode()
    }

    /**
     * Builds both envelopes from mono 16-bit PCM at [ENV_SR].
     *
     * **Why two bands, and not one.** A word does not begin at its loudest point. `Sunce` begins at
     * the s, `she` at the sh, `first` at the f. Those consonants carry real energy but almost none of
     * it is low frequency, so on a broadband envelope they are nearly invisible and the word looks
     * like it starts at the vowel, up to 150 ms late. The pre-emphasised band sees them.
     *
     * Windows overlap four to one, which would be expensive measured directly, so this runs off a
     * prefix sum of squares and each window costs the same regardless of its width.
     */
    fun envelope(pcm: ShortArray): Envelope {
        val n = pcm.size
        val duration = n / ENV_SR.toDouble()
        val nHop = ENV_SR * ENV_HOP_MS / 1000
        val nWin = ENV_SR * ENV_WIN_MS / 1000
        val count = (n - nWin) / nHop + 1
        if (count < 4) return Envelope(DoubleArray(0), DoubleArray(0), duration)

        // Prefix sums in Double: a 16-bit square is at most ~1.07e9, and a minute of speech is under
        // 1e15, well inside the 2^53 where Double still counts integers exactly.
        val sq = DoubleArray(n + 1)
        val sqPre = DoubleArray(n + 1)
        var acc = 0.0
        var accPre = 0.0
        var prev = 0.0
        for (i in 0 until n) {
            val v = pcm[i].toDouble()
            acc += v * v
            sq[i + 1] = acc
            // The pre-emphasis of the reference, y[n] - 0.97*y[n-1], with the first sample kept whole.
            val d = if (i == 0) v else v - 0.97 * prev
            prev = v
            accPre += d * d
            sqPre[i + 1] = accPre
        }
        val env = DoubleArray(count)
        val hi = DoubleArray(count)
        for (k in 0 until count) {
            val a = k * nHop
            env[k] = sqrt((sq[a + nWin] - sq[a]) / nWin)
            hi[k] = sqrt((sqPre[a + nWin] - sqPre[a]) / nWin)
        }
        return Envelope(env, hi, duration)
    }

    /** Noise floor, speech peak, and the range between them. */
    private data class Levels(val floor: Double, val peak: Double, val span: Double)

    /**
     * Levels measured from the clip itself, by percentile rather than by peak.
     *
     * A threshold taken as a fraction of the peak collapses on a clip with one shouted word in it:
     * every quiet word then falls under the bar and stops being detected. Taken from the floor
     * upward, the same clip behaves.
     */
    private fun levels(values: DoubleArray): Levels {
        val s = values.sortedArray()
        val floor = s[(s.size * 0.05).toInt()]
        var peak = s[(s.size * 0.97).toInt()]
        if (peak <= floor) peak = s[s.size - 1]
        return Levels(floor, peak, maxOf(peak - floor, 1e-9))
    }

    /**
     * First and last sustained speech, in seconds, or null where there is none. Either band may
     * report it, so a clip opening on a fricative is not clipped off at the front.
     */
    fun speechSpan(e: Envelope): Pair<Double?, Double?> {
        val (f1, _, s1) = levels(e.env)
        val (f2, _, s2) = levels(e.hi)
        val thr1 = f1 + s1 * 0.10
        val thr2 = f2 + s2 * 0.10
        var onset: Double? = null
        for (i in 0 until e.env.size - 3) {
            if ((e.env[i] > thr1 && e.env[i + 1] > thr1 && e.env[i + 2] > thr1) ||
                (e.hi[i] > thr2 && e.hi[i + 1] > thr2 && e.hi[i + 2] > thr2)
            ) {
                onset = i * W
                break
            }
        }
        var last: Double? = null
        for (i in e.env.size - 1 downTo 1) {
            if ((e.env[i] > thr1 && e.env[i - 1] > thr1) || (e.hi[i] > thr2 && e.hi[i - 1] > thr2)) {
                last = (i + 1) * W
                break
            }
        }
        return onset to last
    }

    /**
     * The quiet stretches strictly between the first and last speech, as [start, end] pairs.
     *
     * This is what the word gap feature crosses. It never re-synthesises anything; it changes how the
     * player travels through quiet the voice already left, so that quiet has to be measured once and
     * carried alongside the word times.
     *
     * A frame counts as quiet only when **both** bands are down. The broadband envelope alone calls a
     * fricative tail silence, and an edit made there eats the s off the end of a word. Each run is
     * then pulled in by a guard at both ends, so an edit inside it can never reach a consonant, and a
     * run that does not survive the guard was never a pause between words to begin with.
     */
    fun silenceRuns(e: Envelope, onset: Double?, last: Double?): List<DoubleArray> {
        if (e.env.isEmpty() || e.hi.isEmpty()) return emptyList()
        val (f1, _, s1) = levels(e.env)
        val (f2, _, s2) = levels(e.hi)
        val thr1 = f1 + s1 * SIL_THR
        val thr2 = f2 + s2 * SIL_THR
        val n = minOf(e.env.size, e.hi.size)
        val raw = ArrayList<Pair<Double, Double>>()
        var i = 0
        while (i < n) {
            if (e.env[i] <= thr1 && e.hi[i] <= thr2) {
                var j = i
                while (j < n && e.env[j] <= thr1 && e.hi[j] <= thr2) j++
                raw.add(i * W to j * W)
                i = j
            } else {
                i++
            }
        }
        val lo = onset ?: 0.0
        val hg = last ?: 1e9
        val g = SIL_GUARD_MS / 1000.0
        val out = ArrayList<DoubleArray>()
        for ((a0, b0) in raw) {
            val a = maxOf(a0, lo) + g
            val b = minOf(b0, hg) - g
            if (b - a >= SIL_MIN_MS / 1000.0) out.add(doubleArrayOf(round3(a), round3(b)))
        }
        return out
    }

    /**
     * Audible starts of words or word groups, each walked back to its true onset.
     *
     * Detection is deliberately strict: energy must climb clear of the floor after a real pause and
     * must stay up afterwards, so plosives inside a word, clicks and breaths do not invent word
     * starts. But detection fires on the loud, unambiguous part of the word, usually the vowel, and
     * that is not where the word begins. So every detection is then walked back down its own energy
     * slope to the foot of the rise, where the sound actually left the floor.
     *
     * The strictness decides **whether** there is a word here. The backtrack decides **when** it
     * started. Two different jobs, and collapsing them into one threshold gives up one or the other.
     */
    fun risePoints(e: Envelope): List<Double> {
        val (f1, _, s1) = levels(e.env)
        val (f2, _, s2) = levels(e.hi)
        val thr1 = f1 + s1 * 0.10
        val low1 = f1 + s1 * 0.04
        val thr2 = f2 + s2 * 0.10
        val low2 = f2 + s2 * 0.04
        val gap = (MIN_PAUSE_MS / (W * 1000.0)).toInt()
        val look = maxOf(3, (50 / (W * 1000.0)).toInt())
        val back = (BACKTRACK_MS / (W * 1000.0)).toInt()
        val need = look * 0.6
        val rises = ArrayList<Double>()
        var quiet = 1_000_000 // the file start counts as a long pause
        val n = e.env.size
        for (i in 0 until n) {
            val loud = e.env[i] > thr1 || e.hi[i] > thr2
            if (e.env[i] < low1 && e.hi[i] < low2) {
                quiet++
                continue
            }
            if (loud && quiet >= gap) {
                var hits = 0
                for (k in i until minOf(i + look, n)) {
                    if (e.env[k] > thr1 * 0.7 || e.hi[k] > thr2 * 0.7) hits++
                }
                if (hits >= need) {
                    var j = i
                    val stop = maxOf(0, i - back)
                    while (j > stop) {
                        val q = j - 1
                        if (e.env[q] < low1 && e.hi[q] < low2) break // reached the silence
                        if (e.env[q] > e.env[j] && e.hi[q] > e.hi[j]) break // reached a local minimum
                        j = q
                    }
                    rises.add(j * W)
                }
            }
            if (loud) quiet = 0
        }
        // Feet that backtracked onto the same place are one onset, not several.
        val out = ArrayList<Double>()
        for (r in rises) if (out.isEmpty() || r > out.last() + 0.03) out.add(r)
        return out
    }

    /**
     * Assigns audible rises to word starts, in order, so the **total** disagreement is smallest.
     * Dynamic programming, the same shape a real forced aligner uses.
     *
     * Not every rise is a word and not every word has a rise: words inside a continuous phrase have
     * no onset of their own. So either side may be skipped at a price, and greedy nearest-matching
     * cannot do this because it cannot see the consequence of a choice two words later.
     *
     * [ANCHOR_SKIP] is the number that matters. Once onsets are backtracked they are trustworthy, so
     * discarding one has to be expensive; at a lower price the solver would rather throw away a real
     * onset than accept a word the engine had placed 0.4 s off, which is precisely the case where the
     * measurement was needed most.
     *
     * Returns pairs of word index to rise time.
     */
    fun matchAnchors(wordTimes: List<Double>, rises: List<Double>): List<Pair<Int, Double>> {
        val n = wordTimes.size
        val m = rises.size
        if (n == 0 || m == 0) return emptyList()
        val dp = Array(m + 1) { DoubleArray(n + 1) }
        for (j in 1..m) dp[j][0] = dp[j - 1][0] + ANCHOR_SKIP
        for (j in 1..m) {
            for (i in 1..n) {
                var best = dp[j][i - 1] // word i has no rise
                val skip = dp[j - 1][i] + ANCHOR_SKIP // rise j is not a word
                if (skip < best) best = skip
                val pair = abs(wordTimes[i - 1] - rises[j - 1])
                if (pair <= ANCHOR_CAP) {
                    val take = dp[j - 1][i - 1] + pair // rise j starts word i
                    if (take < best) best = take
                }
                dp[j][i] = best
            }
        }
        val out = ArrayList<Pair<Int, Double>>()
        var j = m
        var i = n
        while (j > 0 && i > 0) {
            val pair = abs(wordTimes[i - 1] - rises[j - 1])
            if (pair <= ANCHOR_CAP && abs(dp[j][i] - (dp[j - 1][i - 1] + pair)) < 1e-9) {
                out.add((i - 1) to rises[j - 1])
                j--
                i--
            } else if (abs(dp[j][i] - (dp[j - 1][i] + ANCHOR_SKIP)) < 1e-9) {
                j--
            } else {
                i--
            }
        }
        out.reverse()
        return out
    }

    /** What [refine] produces: the moved tokens, the real clip length, and whether anything moved. */
    data class Refined(val tokens: List<MaAlign.Token>, val duration: Double, val changed: Boolean)

    /**
     * Re-anchors word times onto the real waveform. Three passes, like a caption tool:
     *
     * 1. Stretch the whole engine timeline so first and last speech land where the audio really
     *    starts and ends. This alone kills constant lag and overall drift.
     * 2. Pin every audible onset to the word it belongs to and warp everything between them
     *    proportionally.
     * 3. Tidy, so starts strictly increase and every word holds until the next begins.
     *
     * On any trouble the original tokens come back untouched. That is deliberate and worth keeping:
     * a highlight running on the engine's own timing is slightly late, while a highlight running on a
     * failed measurement is anywhere at all.
     */
    fun refine(pcm: ShortArray, tokens: List<MaAlign.Token>): Refined {
        if (tokens.isEmpty()) return Refined(tokens, 0.0, false)
        val e = envelope(pcm)
        if (!e.usable || e.duration <= 0.2) return Refined(tokens, e.duration, false)
        val (onset, last) = speechSpan(e)
        if (onset == null || last == null || last - onset < 0.15) return Refined(tokens, e.duration, false)

        val t0 = tokens.first().t
        val t1 = tokens.maxOf { it.d }
        if (t1 - t0 < 0.05) return Refined(tokens, e.duration, false)

        // 1) affine re-anchor. A scale outside half to double speed is not a measurement, it is a
        //    mistake, so it is refused rather than applied.
        var a = (last - onset) / (t1 - t0)
        if (!(a > 0.5 && a < 2.0)) a = 1.0
        val b = onset - a * t0
        val ts = DoubleArray(tokens.size) { a * tokens[it].t + b }
        val ds = DoubleArray(tokens.size) { a * tokens[it].d + b }

        // 2) anchor warp.
        val rises = risePoints(e)
        val pairs = matchAnchors(ts.toList(), rises)
        val anchors = ArrayList<Pair<Double, Double>>()
        for ((idx, r) in pairs) anchors.add(ts[idx] to r)
        val tailX = maxOf(ds.last(), if (anchors.isNotEmpty()) anchors.last().first + 0.01 else 0.0)
        anchors.add(tailX to minOf(last, e.duration))
        val clean = ArrayList<Pair<Double, Double>>()
        for ((x, y) in anchors) {
            if (clean.isEmpty() || (x > clean.last().first + 1e-3 && y > clean.last().second + 1e-3)) {
                clean.add(x to y)
            }
        }
        if (clean.size >= 2) {
            for (i in ts.indices) {
                ts[i] = warp(ts[i], clean)
                ds[i] = warp(ds[i], clean)
            }
        }

        // 3) tidy.
        var prev = -1.0
        for (i in ts.indices) {
            if (ts[i] <= prev) ts[i] = prev + 0.01
            prev = ts[i]
        }
        val out = ArrayList<MaAlign.Token>(tokens.size)
        for (i in tokens.indices) {
            val nxt = if (i + 1 < ts.size) ts[i + 1] else minOf(last + 0.05, e.duration)
            var d = ds[i]
            if (d <= ts[i] || d > nxt) d = nxt
            if (d <= ts[i]) d = ts[i] + 0.05
            out.add(tokens[i].copy(t = round3(ts[i]), d = round3(d)))
        }
        return Refined(out, e.duration, true)
    }

    /** Piecewise linear through the anchors, and a plain shift outside them. */
    private fun warp(x: Double, clean: List<Pair<Double, Double>>): Double {
        if (x <= clean[0].first) return clean[0].second + (x - clean[0].first)
        for (k in 0 until clean.size - 1) {
            val (x0, y0) = clean[k]
            val (x1, y1) = clean[k + 1]
            if (x <= x1) return y0 + (x - x0) * (y1 - y0) / (x1 - x0)
        }
        val (xN, yN) = clean.last()
        return yN + (x - xN)
    }

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
}
