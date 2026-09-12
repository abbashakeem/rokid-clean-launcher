plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

layout.buildDirectory.set(File(System.getProperty("user.home"), "Library/Caches/hudlauncher-build/root"))
