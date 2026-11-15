buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10") }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.diffplug.spotless") version "8.0.0"
}

spotless {
    kotlin {
        target("app/src/**/*.kt")
        ktfmt("0.59").kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktfmt("0.59").kotlinlangStyle()
    }
    java {
        target("app/src/**/*.java")
        googleJavaFormat("1.28.0")
    }
}
