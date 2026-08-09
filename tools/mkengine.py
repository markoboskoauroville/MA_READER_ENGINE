"""Regenerates engine/src/test/resources/engine_vectors.json.

Cross-check data for MaText and MaWaveform, produced by MA Reader v26's own Python so the Kotlin is
measured against the thing it was ported from rather than against anybody's expectation.

Needs ref_rest.py beside it, lifted verbatim out of the reference:

    { echo "UNIT_CAP = 320"; echo "import re, json, math"; \\
      sed -n '693,750p' 26sh_i_ma_reader_v26_macos.sh; \\
      sed -n '664,690p' 26sh_i_ma_reader_v26_macos.sh; \\
      sed -n '961,968p' 26sh_i_ma_reader_v26_macos.sh; \\
      sed -n '1039,1120p' 26sh_i_ma_reader_v26_macos.sh; \\
      sed -n '1122,1229p' 26sh_i_ma_reader_v26_macos.sh; } > ref_rest.py

No network needed. The PCM is synthesised here rather than decoded from a real clip, deliberately:
the maths is what was ported, and a generated signal with known word positions and known gaps
exercises every branch (fricative onsets, a shouted word, a long pause, a clip that opens on speech)
in a way a single recording never would. Refine is then checked end to end on it.
"""

import base64, json, math, random, re, struct
import ref_rest

SR = 16000


def synth_pcm(words, noise=40.0, seed=7):
    """A fake clip: (start, duration, amplitude, fricative) per word, the rest noise floor."""
    rnd = random.Random(seed)
    total = max(w[0] + w[1] for w in words) + 0.4
    n = int(total * SR)
    out = [0.0] * n
    for i in range(n):
        out[i] = rnd.gauss(0.0, noise)
    for (start, dur, amp, fric) in words:
        a = int(start * SR)
        b = min(n, int((start + dur) * SR))
        for i in range(a, b):
            t = (i - a) / SR
            # Attack over 15 ms so the rise has a real slope to backtrack down.
            env = min(1.0, t / 0.015)
            if fric:
                # Broadband hiss: nearly invisible on the low band, loud on the pre-emphasised one.
                v = rnd.gauss(0.0, amp * 0.55) * env
            else:
                v = amp * env * (math.sin(2 * math.pi * 140 * t) + 0.4 * math.sin(2 * math.pi * 280 * t))
            out[i] += v
    return [max(-32768, min(32767, int(v))) for v in out]


def env_of(pcm):
    """The reference's own pure-Python envelope path, on samples instead of a decoded file."""
    n_hop = SR * 5 // 1000
    n_win = SR * 20 // 1000
    count = (len(pcm) - n_win) // n_hop + 1
    sq = [0.0] * (len(pcm) + 1)
    sqp = [0.0] * (len(pcm) + 1)
    prev = 0
    t1 = t2 = 0.0
    for i, v in enumerate(pcm):
        t1 += float(v) * v
        sq[i + 1] = t1
        d = v - 0.97 * prev if i else float(v)
        prev = v
        t2 += d * d
        sqp[i + 1] = t2
    env = ref_rest._win_rms(sq, n_hop, n_win, count)
    hi = ref_rest._win_rms(sqp, n_hop, n_win, count)
    return env, hi, len(pcm) / float(SR)


TEXTS = [
    "# Naslov\n\nOvo je [link](https://example.com/x) i **podebljano** i `kod`.",
    "One. Two! Three? Four\u2026 Five.",
    "- prva stavka\n- druga stavka\n1. treca\n2. cetvrta",
    "> citat ovdje\n>> dublji citat",
    "| a | b |\n|---|---|\n| 1 | 2 |",
    "Vidi <https://example.com> i mailto:a@b.com i www.primjer.hr kraj.",
    "```\nkod koji se ne cita\n```\nposlije koda.",
    "***\n---\n===\nposlije crte.",
    "Text with  multiple   spaces and space before punctuation .",
    "![slika](x.png) ostaje samo ovo.",
    "[ref][1] i [prazno]() tekst.\n\n[1]: https://example.com",
    "<b>html</b> se mice, <i>ali</i> tekst ostaje.",
    "__naglaseno__ i _kurziv_ i snake_case_ime ostaje.",
    "",
    "   \n\n   ",
    "Bez zavrsne tocke",
    "A" * 400 + ". Kratka.",
    "Jedna recenica koja je jako duga " * 12,
    "Danas je 8. mjesec i imam 25 godina. Sutra je 9.",
]

# (start, duration, amplitude, is_fricative)
CLIPS = {
    "three plain words": [(0.20, 0.30, 6000, False), (0.80, 0.30, 6000, False), (1.40, 0.35, 6000, False)],
    "fricative opening": [(0.15, 0.22, 5000, True), (0.60, 0.30, 6000, False), (1.20, 0.28, 5500, True)],
    "one shouted word": [(0.20, 0.25, 3000, False), (0.75, 0.30, 26000, False), (1.35, 0.25, 3000, False)],
    "long pause between": [(0.15, 0.30, 6000, False), (1.60, 0.30, 6000, False)],
    "opens immediately": [(0.02, 0.30, 6000, False), (0.60, 0.30, 6000, False)],
    "continuous phrase": [(0.20, 1.10, 6000, False), (1.60, 0.30, 6000, False)],
}

# Token timings as align_tokens would have produced them, deliberately a little late and drifting,
# which is exactly the error refine exists to remove.
TOKENS = {
    "three plain words": [(0, 4, 0.28, 0.55), (5, 8, 0.90, 1.15), (9, 14, 1.52, 1.80)],
    "fricative opening": [(0, 5, 0.30, 0.45), (6, 10, 0.70, 0.95), (11, 16, 1.32, 1.55)],
    "one shouted word": [(0, 3, 0.26, 0.50), (4, 8, 0.84, 1.10), (9, 13, 1.44, 1.65)],
    "long pause between": [(0, 4, 0.24, 0.50), (5, 10, 1.70, 1.95)],
    "opens immediately": [(0, 4, 0.10, 0.35), (5, 9, 0.68, 0.95)],
    "continuous phrase": [(0, 4, 0.28, 0.80), (5, 9, 0.85, 1.35), (10, 15, 1.70, 1.95)],
}


def main():
    out = {"text": [], "waveform": []}

    for t in TEXTS:
        out["text"].append({
            "input": t,
            "clean": ref_rest.clean_text(t),
            "units": [[a, b] for (a, b) in ref_rest.split_units(t)],
            "clean_units": [[a, b] for (a, b) in ref_rest.split_units(ref_rest.clean_text(t))],
        })

    for name, words in CLIPS.items():
        pcm = synth_pcm(words)
        env, hi, dur = env_of(pcm)
        W = 0.005
        onset, last = ref_rest._speech_span(env, hi, W)
        runs = ref_rest.silence_runs(env, hi, W, onset, last)
        rises = ref_rest._rise_points(env, hi, W)
        toks = [{"s": s, "e": e, "t": t, "d": d} for (s, e, t, d) in TOKENS[name]]

        # refine_tokens reads its audio from a file, so the two steps it does before the maths are
        # inlined here against the same envelope. Everything after this point is the reference's own.
        ref = None
        changed = False
        if env and hi and dur > 0.2 and onset is not None and last is not None and last - onset >= 0.15:
            t0 = toks[0]["t"]
            t1 = max(x["d"] for x in toks)
            if t1 - t0 >= 0.05:
                a = (last - onset) / (t1 - t0)
                if not (0.5 < a < 2.0):
                    a = 1.0
                b = onset - a * t0
                ref = [{"s": x["s"], "e": x["e"], "t": a * x["t"] + b, "d": a * x["d"] + b} for x in toks]
                word_ts = [w["t"] for w in ref]
                pairs = ref_rest._match_anchors(word_ts, rises)
                anchors = [(word_ts[i], r) for (i, r) in pairs]
                anchors.append((max(ref[-1]["d"], anchors[-1][0] + 0.01 if anchors else 0),
                                min(last, dur)))
                clean = []
                for (x, y) in anchors:
                    if not clean or (x > clean[-1][0] + 1e-3 and y > clean[-1][1] + 1e-3):
                        clean.append((x, y))
                if len(clean) >= 2:
                    def warp(x):
                        if x <= clean[0][0]:
                            return clean[0][1] + (x - clean[0][0])
                        for (x0, y0), (x1, y1) in zip(clean, clean[1:]):
                            if x <= x1:
                                return y0 + (x - x0) * (y1 - y0) / (x1 - x0)
                        xN, yN = clean[-1]
                        return yN + (x - xN)
                    for w in ref:
                        w["t"] = warp(w["t"])
                        w["d"] = warp(w["d"])
                prev = -1.0
                for w in ref:
                    if w["t"] <= prev:
                        w["t"] = prev + 0.01
                    prev = w["t"]
                for i, w in enumerate(ref):
                    nxt = ref[i + 1]["t"] if i + 1 < len(ref) else min(last + 0.05, dur)
                    if w["d"] <= w["t"] or w["d"] > nxt:
                        w["d"] = nxt
                    if w["d"] <= w["t"]:
                        w["d"] = w["t"] + 0.05
                    w["t"] = round(w["t"], 3)
                    w["d"] = round(w["d"], 3)
                changed = True

        out["waveform"].append({
            "name": name,
            # int16 little-endian, base64: a megabyte of decimal integers in a repo is
            # a megabyte nobody can read anyway.
            "pcm_b64": base64.b64encode(struct.pack("<%dh" % len(pcm), *pcm)).decode("ascii"),
            "pcm_len": len(pcm),
            "duration": dur,
            "env_len": len(env),
            "env_head": [round(v, 6) for v in env[:8]],
            "hi_head": [round(v, 6) for v in hi[:8]],
            "onset": onset,
            "last": last,
            "silence": runs,
            "rises": [round(r, 6) for r in rises],
            "tokens": [{"s": s, "e": e, "t": t, "d": d} for (s, e, t, d) in TOKENS[name]],
            "refined": ref if ref else [{"s": x["s"], "e": x["e"], "t": x["t"], "d": x["d"]} for x in toks],
            "changed": changed,
        })

    json.dump(out, open("engine_vectors.json", "w", encoding="utf-8"), ensure_ascii=False)
    print(f"{len(out['text'])} text cases, {len(out['waveform'])} clips")
    for c in out["waveform"]:
        print(f"  {c['name']:22} {c['pcm_len']} samples, {len(c['rises'])} rises, "
              f"{len(c['silence'])} silences, changed={c['changed']}")


main()
