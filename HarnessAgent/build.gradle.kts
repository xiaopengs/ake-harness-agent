// 根目录只做「插件版本协定」，不放任何业务代码、不放任何依赖实现。
// 每个 apply false 的插件都在这里锁版本，子模块 alias(libs.plugins.xxx) 即可。
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    // ⚠ 已移除 `kotlinAndroid`（2026-10 实测：AGP 9.0+ 内置 Kotlin 支持，
    //   再 apply 'org.jetbrains.kotlin.android' 会直接抛
    //   "The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0"。
    //   ⇒ 所有 Android 模块一律不要再引它；Kotlin 版本由根 catalog 的 kotlin 版本钉住。）
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.room) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
