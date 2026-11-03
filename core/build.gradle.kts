import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Reines Kotlin-Modul ohne Android: Datenmodell und Planer.
// Läuft auf jedem Rechner mit Java und wird später von der App eingebunden.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Choco 4.10.x ist Java-8-Bytecode und läuft damit problemlos auf Android.
    implementation("org.choco-solver:choco-solver:4.10.18")
    // Speicherformat der Teamliste (JSON). Reines Kotlin, läuft auf Android und im Test.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
