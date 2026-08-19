buildscript {
    dependencies {
        // AGP 9.x has built-in Kotlin support. Pin the newer KGP runtime used by
        // the Compose compiler plugin instead of applying kotlin-android.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
}
