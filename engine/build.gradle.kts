// A plain Kotlin library on purpose.
//
// No Android plugin, no Compose, no Context: nothing here may know which app is calling it. That is
// what keeps the engine consumable by a keyboard and by a standalone reader without either one
// bending it toward itself. If something here needs an Activity or a View, it belongs in the app.
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation(kotlin("test"))
}
