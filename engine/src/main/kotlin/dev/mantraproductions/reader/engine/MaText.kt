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

/**
 * Turns whatever text arrives into something worth speaking, and cuts it into the units the reader
 * synthesises one at a time.
 *
 * Ported from `clean_text` and `split_units` in MA Reader v26. The order of the substitutions is
 * load-bearing and must not be rearranged: links are unwrapped to their labels before bare URLs are
 * deleted, because doing it the other way round eats the label with the address.
 *
 * Why clean at all: a voice reading a pasted article will otherwise say "open square bracket" and
 * spell out an https address, which is thirty seconds of nothing. Everything here exists to keep a
 * word on the page and a word in the ear the same thing.
 */
object MaText {

    /** Longest unit handed to the voice. Sentences beyond this are cut at a space. */
    const val UNIT_CAP = 320

    // (?U) throughout: Java's \s, \w and \S are ASCII-only unless asked, and pasted text is full of
    // non-breaking spaces and accented letters. Without it the two implementations disagree about
    // where words and sentences even are.
    private val FENCE = Regex("""(?Um)^\s*(?:```+|~~~+).*$""")
    private val IMG = Regex("""!\[[^\]]*\]\([^)]*\)""")
    private val AUTOLINK = Regex("""<((?:https?|ftp|mailto):[^>\s]+)>""", RegexOption.IGNORE_CASE)
    private val LINK = Regex("""\[([^\]]*)\]\([^)]*\)""")
    private val REFLINK = Regex("""\[([^\]]+)\]\[[^\]]*\]""")
    private val REFDEF = Regex("""(?Um)^\s{0,3}\[[^\]]+\]:\s+\S.*$""")
    private val URL = Regex("""(?:(?:https?|ftp)://|www\.)[^\s<>)\]}"']+""", RegexOption.IGNORE_CASE)
    private val MAILTO = Regex("""\bmailto:[^\s<>)\]}"']+""", RegexOption.IGNORE_CASE)
    private val HTML = Regex("""</?[A-Za-z][^>]*>""")
    private val CODE = Regex("""`+([^`]*)`+""")
    private val EMPH_AST = Regex("""(\*\*|\*|~~)(?=\S)(.+?)(?<=\S)\1""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val EMPH_US = Regex("""(?U)(?<![\w])(__|_)(?=\S)(.+?)(?<=\S)\1(?![\w])""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val HEADING = Regex("""^\s{0,3}#{1,6}\s*""")
    private val QUOTE = Regex("""^\s{0,3}>+\s?""")
    private val BULLET = Regex("""^(\s*)(?:[-*+]|\d+[.)])\s+""")
    private val RULE = Regex("""^\s{0,3}(?:(?:[-*_]\s*){3,}|=+)\s*$""")

    private val EMPTY_PARENS = Regex("""\(\s*\)""")
    private val EMPTY_BRACKETS = Regex("""\[\s*\]""")
    private val RUN_OF_SPACES = Regex("""[ \t]+""")
    private val SPACE_BEFORE_PUNCT = Regex("""(?U)\s+([.,;:!?])""")
    private val TRAILING_SPACE = Regex(""" *\n""")
    private val BLANK_RUN = Regex("""\n{3,}""")

    /** Markdown, HTML and addresses out; plain words in. */
    fun cleanText(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        var text = input.replace("\r\n", "\n").replace("\r", "\n")
        text = FENCE.replace(text, "")
        text = IMG.replace(text, "")
        text = AUTOLINK.replace(text, "")
        text = LINK.replace(text, "$1")
        text = REFLINK.replace(text, "$1")
        text = REFDEF.replace(text, "")
        text = URL.replace(text, "")
        text = MAILTO.replace(text, "")
        text = HTML.replace(text, "")
        text = CODE.replace(text, "$1")
        // Emphasis nests, so a bounded number of passes. Three, and stop early once a pass changes
        // nothing: unbounded looping on hostile input is how a reader hangs on a paste.
        repeat(3) {
            val next = EMPH_US.replace(EMPH_AST.replace(text, "$2"), "$2")
            if (next == text) return@repeat
            text = next
        }
        val lines = ArrayList<String>()
        for (raw in text.split("\n")) {
            if (RULE.containsMatchIn(raw) && RULE.find(raw)?.range?.first == 0) continue
            var line = HEADING.replace(raw, "")
            line = QUOTE.replace(line, "")
            line = BULLET.replace(line, "$1")
            if (line.contains('|')) {
                val stripped = line.trim()
                // A table rule line, all pipes dashes and colons, is furniture and not a sentence.
                if (stripped.isNotEmpty() && stripped.all { it in "|:- " }) continue
                line = line.replace("|", " ")
            }
            lines.add(line)
        }
        text = lines.joinToString("\n")
        text = EMPTY_PARENS.replace(text, "")
        text = EMPTY_BRACKETS.replace(text, "")
        text = RUN_OF_SPACES.replace(text, " ")
        text = SPACE_BEFORE_PUNCT.replace(text, "$1")
        text = TRAILING_SPACE.replace(text, "\n")
        text = BLANK_RUN.replace(text, "\n\n")
        return text.trim()
    }

    private val SENT = Regex("""(?U)(?<=[.!?\u2026])\s+""")

    /** Character spans of each sentence, blank ones dropped. */
    fun splitSentences(text: String): List<IntRange> {
        val spans = ArrayList<Pair<Int, Int>>()
        var start = 0
        for (m in SENT.findAll(text)) {
            spans.add(start to m.range.first)
            start = m.range.last + 1
        }
        if (start < text.length) spans.add(start to text.length)
        return spans.filter { text.substring(it.first, it.second).isNotBlank() }
            .map { it.first until it.second }
    }

    /**
     * The units actually handed to the voice: one sentence each, and anything longer than [cap] cut
     * at the last space that fits.
     *
     * The cap is not decoration. A single request carries one unit, and a unit that is a whole
     * paragraph means the reader says nothing at all until the entire paragraph has been synthesised,
     * which on a phone is the difference between reading and waiting. Cutting at a space rather than
     * mid-word keeps the seam inaudible.
     */
    fun splitUnits(input: String, cap: Int = UNIT_CAP): List<IntRange> {
        val text = input.replace("\r\n", "\n").replace("\r", "\n")
        val units = ArrayList<IntRange>()
        for (sentence in splitSentences(text)) {
            val end = sentence.last + 1
            var s = sentence.first
            while (end - s > cap) {
                var cut = text.lastIndexOf(' ', s + cap - 1)
                // lastIndexOf searches the whole prefix, so a space before the window does not count.
                if (cut < s || cut >= s + cap) cut = -1
                if (cut <= s) cut = s + cap
                if (text.substring(s, cut).isNotBlank()) units.add(s until cut)
                s = cut
                while (s < end && (text[s] == ' ' || text[s] == '\n' || text[s] == '\t')) s++
            }
            if (end > s && text.substring(s, end).isNotBlank()) units.add(s until end)
        }
        return units
    }
}
