# MA Reader Engine

The reader engine, in one place, owned by neither app that uses it.

## Why this repository exists

Two projects need the same two things:

- **LLL**, the reader view inside Talk to Type — `markoboskoauroville/DictateKeyboard`
- **MA Reader Android**, the standalone port — `markoboskoauroville/MA_READER_ANDROID`

Both need a Kotlin client for the Microsoft Edge speech WebSocket, and both need the word timing
alignment ported from MA Reader v26.

The first arrangement tried was a canonical folder plus a rule for keeping copies in step. Marko
rejected it, correctly: two copies with a synchronisation rule are still two copies, and a rule that
depends on somebody remembering to follow it is a rule that eventually is not followed. The failure
would be quiet. A fix lands in one app, the other keeps the old behaviour, and the symptom is a
highlight that no longer sits on the word being spoken.

**So there are no copies. There is one repository, and both apps consume it.**

## How the apps consume it

As a **git submodule**, included as a Gradle module. Not a published artifact: publishing means
pinning a version, pinning a version means the two apps can be on different versions, and two
versions is the problem this repository was made to remove.

In each consuming app:

```
git submodule add https://github.com/markoboskoauroville/MA_READER_ENGINE.git engine-repo
```

`settings.gradle.kts`:

```kotlin
include(":engine")
project(":engine").projectDir = file("engine-repo/engine")
```

`app/build.gradle.kts`:

```kotlin
implementation(project(":engine"))
```

CI must check out submodules or the build fails with a missing module, which is a confusing error for
the cause:

```yaml
- uses: actions/checkout@v4
  with:
    submodules: recursive
```

### Picking up a change

A submodule points at one commit, so a change here does not reach an app until the app is told to
move. In the app:

```
git submodule update --remote engine-repo && git add engine-repo && git commit -F msg.txt && git push
```

This is deliberate. It means a change here can never break both apps at once without somebody
choosing it, and it means each app can be built at a known engine commit rather than at whatever
happened to be on main that minute.

**Marko's rule, and it still applies:** when the two apps are on different engine commits, compare
the dates and move the older one up to the newer. There is only ever one version of the code, so this
is now a question of when each app picks a change up, never of which version is correct.

## What goes here

| File | What it is |
|---|---|
| `MaEdgeVoice.kt` | The Edge speech WebSocket client. Voice and text in, audio plus word boundaries out. **Done, and proven against the live service.** |
| `MaAlign.kt` | The port of `align_tokens` from MA Reader v26. **Done, cross-checked against the Python.** |
| `MaText.kt` | `clean_text` and `split_units`: Markdown and addresses out, sentences into speakable units. **Done.** |
| `MaWaveform.kt` | `refine_tokens` and the envelope machinery: every word moved onto where it is actually spoken. **Done.** |

Nothing else. Anything that knows about a keyboard, an Activity, a WebView or a Compose theme belongs
in the app that has one, not here. The test for whether something belongs: **both apps must need it,
and it must not care which of them is calling.**

## Two things not to lose

**Ask for word boundaries explicitly.** edge-tts 7.x changed its default boundary type from
`WordBoundary` to `SentenceBoundary`. Constructed the old way it returns no word events at all, and
the app silently falls back to spreading words evenly across the clip rather than measuring them.
Nothing errors. The highlight is simply wrong, and it is wrong in a way that looks like a rendering
bug rather than a synthesis one.

**Port the alignment faithfully, branch for branch.** Every branch in `align_tokens` exists because
something went wrong once: the voice expanding a number, spelling an acronym, reading a symbol aloud,
emitting boundaries matching nothing visible. Its critical rule is that when matching fails it still
returns a left-to-right spread proportional to word length, rather than dumping every word at zero,
which used to pin the highlight to the last word for a whole sentence. A cleaner rewrite loses the
recovery cases, and the loss does not show until a paragraph has a number in it.

## Reference implementation

`markoboskoauroville/DictateKeyboard`, at `reference/26sh_i_ma_reader_v26_macos.sh`. Marko's MA
Reader v26 for macOS, roughly a year of work, and the specification for everything here. The
synthesis call is around line 1365 and is the least valuable part of it. `align_tokens` is around
line 828 and is the reason this repository exists.

## The speech protocol, and how it breaks

Written down at the point `MaEdgeVoice` was built, because most of it is invisible in the code and
none of it is guessable.

edge-tts is only a *client*: it opens a WebSocket to Microsoft's readaloud endpoint, sends SSML, and
reads back binary audio frames interleaved with JSON word boundary frames. `MaEdgeVoice` does the
same thing on OkHttp. The library itself is Python and cannot run inside an input method.

Every item below is checked by the service, and each is a flat rejection before a single frame is
exchanged. Three of them were wrong from memory and were only found by reading edge-tts 7.2.8's own
source after a 403 and then a 400:

- The origin is `chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold`.
- `Sec-MS-GEC-Version` must carry a **current** Chromium version, at the time of writing
  `143.0.3650.75`. A stale one is 403. **This will go stale again, and it is the first thing to
  check when a reader goes quiet.**
- A `Cookie: muid=<32 hex>` header is now required.
- The `speech.config` message ends with a trailing CRLF after its JSON.
- The SSML frame's `X-Timestamp` ends with a `Z` that does not belong there. It is a bug in Edge
  itself and the service expects it.

And one that is ours rather than Microsoft's: do **not** set `Sec-WebSocket-Version` yourself. OkHttp
writes it, and the duplicate header is answered with HTTP 400, which looks nothing like a header
problem.

**A wrong clock reads as forbidden**, because the token is a hash of the current time rounded to five
minutes. `synthesize` treats a 403 as a clock problem rather than an error: it takes the truth from
the server's own `Date` header, remembers the difference for the session, and retries once. Worth
having on a phone, where nothing guarantees the clock.

## Proof

Nine tests run offline on every build. The security token is checked against vectors cross computed
from edge-tts's own implementation, and the frame parsing runs against a metadata frame captured
verbatim from the service.

The tenth speaks for real. It is gated behind `TTT_LIVE_VOICE=1` so an ordinary build never depends
on Microsoft being reachable, and CI runs it on every push and once a night, because this endpoint
can change without anything in this repository changing. CI retries it once: a live call dropped once
in about a dozen runs, and one blip should not read as a broken protocol.

```
Danas je 8. mjesec i imam 25 godina.        hr-HR-GabrijelaNeural
26064 bytes of mp3, 4.344 s, 6 boundaries
   0.100 +0.475  Danas
   0.575 +0.088  je
   0.663 +0.825  8. mjesec
   1.488 +0.188  i
   1.675 +0.463  imam
   2.138 +1.350  25 godina
```

**Six boundaries for eight visible words, and that is the brief for `MaAlign`.** The engine answered
`8. mjesec` and `25 godina` as single boundaries covering two visible words each. Boundary text
cannot be matched to visible text one to one, which is precisely the mess `align_tokens` exists to
absorb. This is the sentence to test it against.

All four voices were checked when this was written, along with times, dates, `&` and `<`, and
Croatian diacritics surviving the round trip intact.

## Voices

Four, no more. Two languages, female and male in each.

| | female | male |
|---|---|---|
| English (UK) | `en-GB-SoniaNeural` | `en-GB-RyanNeural` |
| Croatian | `hr-HR-GabrijelaNeural` | `hr-HR-SreckoNeural` |

## The alignment, and how it is checked

`MaAlign.alignTokens` is a port of `align_tokens`, branch for branch. It is checked against the
thing it was ported from rather than against anybody's expectation: `align_vectors.json` holds 21
cases run through MA Reader v26's own Python, and the Kotlin must reproduce every character offset
exactly and every time to within a nanosecond. Seven cases carry real boundaries captured from the
live service in both languages, chosen for what breaks matching: numbers, times, dates, currency,
acronyms and Croatian diacritics. The rest reach branches the service will not produce on demand.

Regenerate the vectors with `mkvectors.py` if the reference itself changes. Never edit them by hand:
a hand-edited vector is a test that agrees with whoever edited it.

**The cross-check earned its place immediately.** The first run differed in one case out of 21. The
reference returns straight out of its no-match branch, skipping the pass that derives end times, and
the port had helpfully sent that path through the same ending as every other one. It looks tidier
and it is wrong: with no clip length known it cuts the last word to its own start plus 0.4 s, shorter
than the spread it was just given. Nothing about that is visible by reading the two side by side.

Two Kotlin details that would otherwise diverge silently from Python, both handled and both worth
knowing before touching `norm`:

- Java's `\S` is ASCII only unless asked, so the token regex carries `(?U)`. Without it a
  non-breaking space in a pasted article makes the two implementations disagree about where words
  even are.
- Python's `str.isalnum()` is wider than Kotlin's `isLetterOrDigit()`: it also counts numeric
  characters like ½ and Ⅷ.

### One thing to look at, not yet changed

In the proven sentence, the tokens `8.` and `25` come out with a start time equal to their end time,
so they are lit for zero seconds and the highlight steps from `je` straight to `mjesec`. This falls
out of the reference honestly: the number and the word after it arrive as one boundary, that
boundary's span is handed to the number, and the tidying pass then trims it back to where the next
word starts.

This is reported rather than fixed. The port is faithful and should stay that way until Marko says
otherwise, and `refine_tokens` has not been ported yet, so it is not yet known whether the waveform
pass moves these onsets apart in practice. But a number that never lights is worth a decision, since
the lit word is the point of the whole thing.

## The waveform pass

`MaWaveform.refine` listens to the finished clip and moves every word onto where it is actually
spoken. The engine's own boundaries describe what the synthesiser intended rather than what came out
of the encoder, and they are systematically late; the reference measured a mean error of about 80 ms
falling to about 18 ms with this pass, and around 150 ms on words opening with s, sh or f.

**It takes decoded PCM, not a file.** The reference shells out to ffmpeg, which an app cannot do, and
the maths does not care where the samples came from. The caller decodes to mono 16-bit at 16 kHz
(MediaCodec on Android) and hands over a `ShortArray`. That is the whole reason this can live in a
module with no Android in it and still be tested in seconds.

Two things in here look like details and are not:

- **Two envelopes, not one.** A word does not begin at its loudest point: `Sunce` begins at the s,
  `first` at the f. Those consonants carry real energy but almost none of it is low frequency, so a
  broadband envelope alone puts the word at the vowel, up to 150 ms late. The second band is
  pre-emphasised and sees them.
- **Onsets are matched to words by dynamic programming**, not by nearest neighbour. Not every rise is
  a word and not every word has a rise, since words inside a continuous phrase have no onset of their
  own, so either side may be skipped at a price. Greedy matching cannot see the consequence of a
  choice two words later; the global view can.

**On any trouble the original tokens come back untouched**, and that is deliberate. A highlight
running on the engine's timing is slightly late. A highlight running on a failed measurement is
anywhere at all.

## Checking the maths without a recording

The waveform vectors are synthesised rather than recorded. A generated signal with known word
positions hits every branch that matters: a fricative opening, a shouted word among quiet ones, a
long pause, a clip that starts on speech with no lead-in, and a continuous phrase where the middle
word has no onset at all. One real recording would reach maybe two of those, and would still only
prove the maths it happened to touch.

126 tests run in the engine now: 9 offline on the speech protocol, 1 live, and the rest cross-checked
against MA Reader v26's own Python for alignment, text and waveform.
