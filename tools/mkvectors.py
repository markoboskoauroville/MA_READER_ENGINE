"""Regenerates engine/src/test/resources/align_vectors.json.

The alignment port is checked against MA Reader v26's own Python rather than against anybody's
expectation, so the expected values in that file must come from the reference itself. This script
produces them. Never edit the vectors by hand: a hand edited vector is a test that agrees with
whoever edited it.

It needs two things next to it:

  ref_align.py   the reference's own align_tokens, lifted verbatim out of MA Reader v26:

      { echo "import re, unicodedata"; \
        sed -n '819,938p' 26sh_i_ma_reader_v26_macos.sh; } > ref_align.py

    That file lives at reference/26sh_i_ma_reader_v26_macos.sh in
    markoboskoauroville/DictateKeyboard. Check the line numbers still bracket _TOKEN_RE through the
    end of align_tokens before trusting them.

  websockets     pip install websockets

Seven of the cases are synthesised live, so this needs a network. The rest are written out below and
reach branches the service will not produce on demand.
"""

import asyncio, hashlib, json, ssl, time, uuid, secrets, websockets
import ref_align

TRUSTED = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

def gec():
    t = time.time() + 11644473600
    t -= t % 300
    t *= 1e7
    return hashlib.sha256(f"{t:.0f}{TRUSTED}".encode()).hexdigest().upper()

def stamp():
    return time.strftime("%a %b %d %Y %H:%M:%S GMT+0000 (Coordinated Universal Time)", time.gmtime())

async def synth(text, voice):
    cid = uuid.uuid4().hex
    url = (f"wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
           f"?TrustedClientToken={TRUSTED}&Sec-MS-GEC={gec()}"
           f"&Sec-MS-GEC-Version=1-143.0.3650.75&ConnectionId={cid}")
    h = {"Origin": "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold",
         "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0",
         "Accept-Encoding": "gzip, deflate, br, zstd", "Accept-Language": "en-US,en;q=0.9",
         "Pragma": "no-cache", "Cache-Control": "no-cache",
         "Cookie": "muid=%s;" % secrets.token_hex(16).upper()}
    bounds, nbytes = [], 0
    async with websockets.connect(url, additional_headers=h, ssl=ssl.create_default_context(),
                                  max_size=None, open_timeout=25) as ws:
        cfg = ('{"context":{"synthesis":{"audio":{"metadataoptions":'
               '{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"true"},'
               '"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}')
        await ws.send(f"X-Timestamp:{stamp()}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n{cfg}\r\n")
        esc = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        ssml = (f"<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                f"<voice name='{voice}'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>{esc}"
                f"</prosody></voice></speak>")
        await ws.send(f"X-RequestId:{cid}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:{stamp()}Z\r\nPath:ssml\r\n\r\n{ssml}")
        async for m in ws:
            if isinstance(m, str):
                if "Path:audio.metadata" in m:
                    for meta in json.loads(m.split("\r\n\r\n", 1)[1]).get("Metadata", []):
                        if meta.get("Type") == "WordBoundary":
                            d = meta["Data"]
                            bounds.append({"t": d["Offset"] / 1e7, "d": d["Duration"] / 1e7,
                                           "w": d["text"]["Text"]})
                elif "Path:turn.end" in m:
                    break
            else:
                hl = int.from_bytes(m[:2], "big")
                nbytes += max(0, len(m) - 2 - hl)
    return bounds, nbytes * 8.0 / 48000.0

LIVE = [
    ("hr", "Danas je 8. mjesec i imam 25 godina.", "hr-HR-GabrijelaNeural"),
    ("hr", "U 2026. godini čitam 3 knjige mjesečno, što je 36 godišnje.", "hr-HR-GabrijelaNeural"),
    ("hr", "Došao je u 17:45 na Trg bana Jelačića.", "hr-HR-SreckoNeural"),
    ("hr", "Čekaj, rekla je ona, đak je već otišao.", "hr-HR-GabrijelaNeural"),
    ("en", "The meeting is at 14:30 on 5 March, room 12.", "en-GB-SoniaNeural"),
    ("en", "NASA and the BBC agreed on 3 points.", "en-GB-RyanNeural"),
    ("en", "It cost $1,250.50 in 2019.", "en-GB-SoniaNeural"),
]

async def main():
    cases = []
    for lang, text, voice in LIVE:
        bounds, dur = await synth(text, voice)
        total = max((b["t"] + b["d"] for b in bounds), default=0.0)
        cases.append({"name": f"live {lang}: {text[:34]}", "sentence": text,
                      "bounds": bounds, "total": round(total, 6)})
        print(f"{len(bounds):3d} bounds  {text}")

    # Cases the live service will not produce on demand, but that the reader will meet.
    synthetic = [
        # Nothing matched at all: the fallback that must still travel left to right.
        {"name": "no boundary matches anything", "sentence": "Alpha beta gamma delta.",
         "bounds": [{"t": 0.1, "d": 0.3, "w": "zzz"}, {"t": 0.5, "d": 0.3, "w": "qqq"}],
         "total": 2.0},
        {"name": "no bounds at all, total known", "sentence": "One two three four five.",
         "bounds": [], "total": 3.0},
        {"name": "no bounds and no total", "sentence": "One two three.", "bounds": [], "total": None},
        {"name": "empty sentence", "sentence": "", "bounds": [], "total": None},
        {"name": "whitespace only", "sentence": "   \n  ", "bounds": [], "total": 1.0},
        {"name": "single word", "sentence": "Zdravo",
         "bounds": [{"t": 0.1, "d": 0.5, "w": "Zdravo"}], "total": 0.8},
        {"name": "punctuation between words", "sentence": "Da -- ne , mozda .",
         "bounds": [{"t": 0.1, "d": 0.3, "w": "Da"}, {"t": 0.6, "d": 0.3, "w": "ne"},
                    {"t": 1.2, "d": 0.5, "w": "mozda"}], "total": 2.0},
        # A stray boundary in the middle: the resync guard.
        {"name": "stray boundary to skip", "sentence": "jedan dva tri",
         "bounds": [{"t": 0.1, "d": 0.3, "w": "jedan"}, {"t": 0.5, "d": 0.2, "w": "xxxxx"},
                    {"t": 0.8, "d": 0.3, "w": "dva"}, {"t": 1.2, "d": 0.3, "w": "tri"}],
         "total": 1.6},
        # One visible word spoken as several boundaries: the accumulate branch.
        {"name": "one word built from several boundaries", "sentence": "ABC stiže",
         "bounds": [{"t": 0.1, "d": 0.2, "w": "A"}, {"t": 0.3, "d": 0.2, "w": "B"},
                    {"t": 0.5, "d": 0.2, "w": "C"}, {"t": 0.8, "d": 0.4, "w": "stiže"}],
         "total": 1.4},
        # First words unmatched: the hold-steady fill before the first known time.
        {"name": "unmatched head", "sentence": "aaa bbb ccc ddd",
         "bounds": [{"t": 1.0, "d": 0.3, "w": "ccc"}, {"t": 1.4, "d": 0.3, "w": "ddd"}],
         "total": 2.0},
        # Trailing words unmatched, no total: the 0.45 per word tail.
        {"name": "unmatched tail without total", "sentence": "aaa bbb ccc ddd",
         "bounds": [{"t": 0.1, "d": 0.3, "w": "aaa"}], "total": None},
        # Accents folded, case ignored.
        {"name": "accent folding and case", "sentence": "ČITAM Žito Šuma",
         "bounds": [{"t": 0.1, "d": 0.4, "w": "citam"}, {"t": 0.6, "d": 0.4, "w": "ZITO"},
                    {"t": 1.1, "d": 0.4, "w": "suma"}], "total": 1.8},
        # Words of very different length: the width weighting.
        {"name": "width weighted interpolation", "sentence": "a bbbbbbbbbb cc ddd e",
         "bounds": [{"t": 0.0, "d": 0.1, "w": "a"}, {"t": 2.0, "d": 0.2, "w": "e"}],
         "total": 2.5},
        # total smaller than the last known time: the tail guard.
        {"name": "total earlier than last boundary", "sentence": "prvi drugi treci",
         "bounds": [{"t": 0.1, "d": 0.2, "w": "prvi"}, {"t": 2.0, "d": 0.2, "w": "drugi"}],
         "total": 1.0},
    ]
    cases.extend(synthetic)

    out = []
    for c in cases:
        exp = ref_align.align_tokens(c["sentence"], c["bounds"], c["total"])
        out.append({**c, "expected": [{"s": t["s"], "e": t["e"],
                                       "t": round(t["t"], 9), "d": round(t["d"], 9)} for t in exp]})
    json.dump(out, open("align_vectors.json", "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    print(f"\n{len(out)} cases, {sum(len(c['expected']) for c in out)} tokens")

asyncio.run(main())
