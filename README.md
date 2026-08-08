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
| `MaEdgeVoice.kt` | The Edge speech WebSocket client. Voice and text in, audio plus word boundaries out. |
| `MaAlign.kt` | The port of `align_tokens` and `refine_tokens` from MA Reader v26. |

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
