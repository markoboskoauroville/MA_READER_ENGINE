// A plain Kotlin library on purpose.
//
// No Android plugin, no Compose, no Context: nothing here may know which app is calling it. That is
// what keeps the engine consumable by a keyboard and by a standalone reader without either one
// bending it toward itself. If something here needs an Activity or a View, it belongs in the app.
//
// It has a second benefit worth protecting: with no Android in it the whole module compiles and its
// tests run in seconds on any machine, which is the only reason the speech protocol can be proven
// before it ever reaches a phone. If something later genuinely needs Android (decoding audio for
// refine_tokens is the likely one), put that part behind an interface the app implements rather
// than turning this into an Android library and losing the fast test.
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // OkHttp 5 rather than 4: Talk to Type is already on 5.3.0, and an included module resolving to
    // a different major than its consumer is exactly the kind of quiet mismatch this repository
    // exists to avoid. MaEdgeVoice also uses fastFallback, which is 5 only, to race IPv6 and IPv4
    // instead of stalling on a dead route, which matters on a phone.
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // The service answers word boundaries as JSON.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation("io.kotest:kotest-runner-junit5:6.1.11")
    testImplementation("io.kotest:kotest-assertions-core:6.1.11")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The live proof is only a proof if its output is readable: a green tick on its own says nothing
    // about whether the highlight would have had anything to follow.
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
    // The one test that speaks for real is gated, so an ordinary build never depends on Microsoft
    // being reachable. CI turns it on.
    environment("TTT_LIVE_VOICE", System.getenv("TTT_LIVE_VOICE") ?: "0")
}
