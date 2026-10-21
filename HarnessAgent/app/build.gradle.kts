plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.ake.harnessagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ake.harnessagent"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9：kotlinOptions 已移除，改由内置 Kotlin 的 compilerOptions 指定
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "META-INF/*.version"
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:contract"))
    implementation(project(":core:ports"))
    implementation(project(":core:model-api"))
    implementation(project(":llm"))
    implementation(project(":browser"))
    implementation(project(":agent"))
    implementation(project(":mcp"))
    implementation(project(":data"))
    implementation(project(":ui"))

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // ── MCP 装配（霍承钧 MCP_BRIDGE.md §4）
    // :app 是唯一能同时看见 :mcp(L3) 与 :agent(L4) 的组合根，桥接类放这里；
    // `project(":mcp")` 已在上面的模块列表里声明，这里只补 ktor engine。
    // McpHiltModule 显式用 OkHttp engine（Android 上默认 engine 有连接复用问题）。
    implementation(libs.ktor.client.okhttp)

    testImplementation(libs.bundles.testUnit)
}
