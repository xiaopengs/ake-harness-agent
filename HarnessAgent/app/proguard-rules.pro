# ═══════════════════════════════════════════════════════════════════════════
# Ake Harness Agent · R8/ProGuard 规则
#
# 原则：**先收紧，再按需放行**。每加一条 -keep 都要写清「为什么必须留」，
# 因为本项目是端侧 App，包体积直接决定低端机能不能装得下、更新愿不愿意下。
#
# 与 androidx 自带规则的关系：Room / Compose / Hilt / lifecycle 都在自己的 AAR 里
# 带了 consumer ProGuard 规则，AGP 会自动合并。下面只补**它们覆盖不到**的部分：
#   ① 我们自己代码里被反射/序列化/JNI 触碰的类型
#   ② 第三方 SDK 里不带规则或规则不全的部分（LiteRT-LM、MCP SDK）
# ═══════════════════════════════════════════════════════════════════════════

# ───────────────────────────────────────────────────────────────────────────
# 0. 通用属性
# ───────────────────────────────────────────────────────────────────────────
# Signature/InnerClasses：kotlinx.serialization 与 Room 都要靠泛型签名找类型
# *Annotation*：Room 的 @Entity/@ColumnInfo、Hilt 的 @Inject 不能被剥
# EnclosingMethod：匿名类/局部类的定位信息，崩溃栈回溯要用
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, Exceptions

# 崩溃栈要能还原到源码行（配合 mapping.txt）
-keepattributes SourceFile, LineNumberTable
# 把所有文件的源文件名统一成 "SourceFile"，避免反编译后暴露包结构
-renamesourcefileattribute SourceFile

# ───────────────────────────────────────────────────────────────────────────
# 1. kotlinx.serialization
#    :mcp 用 kotlinx.serialization 编解码 MCP 协议的 JSON-RPC 报文。
#    它的序列化器是编译期生成的（$serializer / Companion.serializer()），
#    R8 不知道「这个类是 JSON 的入口」，会当成死代码删掉 ⇒ 运行时才炸。
# ───────────────────────────────────────────────────────────────────────────
-keepclassmembers,allowoptimization class com.ake.**$serializer {
    <fields>;
    <methods>;
}
# $$serializer 是 generated serializer 的持有类，includedescriptorclasses 保留其描述符
-keep,includedescriptorclasses class com.ake.**$$serializer { *; }
# Companion.serializer() 被 Json.encodeToString 反射调用
-keepclassmembers class com.ake.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.ake.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# @Serializable / @SerialName 必须在类上保留，否则字段名被混淆后协议对不上
-keep @kotlinx.serialization.Serializable class com.ake.** { *; }
# MCP Kotlin SDK 自带的可序列化类型（KMP 发布物，规则不全）
-keep class io.modelcontextprotocol.kotlin.sdk.** { *; }
-keep class kotlinx.serialization.json.** { *; }
-dontwarn kotlinx.serialization.**

# ───────────────────────────────────────────────────────────────────────────
# 2. Room（:data）
#     Room 生成 _Impl 类后靠**类名字符串**在建库时反射实例化：
#     Class.forName("...AkeDatabase_Impl")。名字被混淆 ⇒ 启动即崩。
#     DAO_Impl 同理，且它内部按字段名拼 SQL。
# ───────────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class androidx.room.RoomDatabase { *; }
# 我们的库与全部生成实现（com.ake.data.persist.db.*）
-keep class com.ake.data.persist.db.AkeDatabase { *; }
-keep class com.ake.data.persist.db.AkeDatabase_Impl { *; }
-keep class com.ake.data.persist.db.**_Impl { *; }
-keep class com.ake.data.persist.db.**Dao_Impl { *; }
# 实体与 DAO 接口：Room 按字段名生成 SQL 列，字段名不能被改
-keep class com.ake.data.persist.db.entity.** { *; }
-keep interface com.ake.data.persist.db.dao.** { *; }
# 带 Room 注解的字段一律保留
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}
# BundledSQLiteDriver（androidx.sqlite 2.7.1）走 JNI，不能动
-keep class androidx.sqlite.** { *; }
-keep class androidx.sqlite.driver.** { *; }
-keep class androidx.sqlite.db.** { *; }
-dontwarn androidx.room.paging.**
-dontwarn androidx.sqlite.jdbc.**

# ───────────────────────────────────────────────────────────────────────────
# 3. LiteRT-LM（:llm）—— 端侧推理引擎，本项目唯一的 JNI 重灾区
#     LiteRT-LM 的 AAR 里带 .so，Java 侧通过 JNI 名（Java_包名_类名_方法名）找实现。
#     类名/方法名被混淆 ⇒ UnsatisfiedLinkError，且是**运行时**才崩，CI 抓不到。
#     这里整体保留 com.google.ai.edge.** 是刻意的：引擎内部还有反射加载 backend，
#     挑着 keep 迟早漏一个，而这部分体积占比很小。
# ───────────────────────────────────────────────────────────────────────────
-keep class com.google.ai.edge.litertlm.** { *; }
-keep interface com.google.ai.edge.litertlm.** { *; }
-keep class com.google.ai.edge.** { *; }
# 兜底：任何 native 方法与其所属类都不得动（全局 JNI 保护）
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keepclassmembers class * {
    native <methods>;
}
# 引擎通过 ServiceLoader / 反射探测 backend（GPU/OpenCL/CPU），资源文件要留
-keep class com.google.ai.edge.litertlm.Backend { *; }
-keepclassmembers enum com.google.ai.edge.litertlm.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-dontwarn com.google.ai.edge.**

# ───────────────────────────────────────────────────────────────────────────
# 4. MCP SDK + Ktor
#     MCP 走 JSON-RPC over SSE/stdio，Ktor 用 ServiceLoader 装 engine（OkHttp）。
#     两者都依赖 SPI 文件与反射实例化，R8 会误删。
# ───────────────────────────────────────────────────────────────────────────
-keep class io.modelcontextprotocol.** { *; }
-keep interface io.modelcontextprotocol.** { *; }
-dontwarn io.modelcontextprotocol.**
# Ktor：OkHttp engine 由 ServiceLoader 加载
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
# SSE 插件收进 ktor-client-core（见 libs.versions.toml 的红线注释）
-keep class io.ktor.client.plugins.sse.** { *; }

# ───────────────────────────────────────────────────────────────────────────
# 5. WebView JS Bridge（:browser）—— 本项目最容易踩的一颗雷
#     DomExtractBridge 用 @JavascriptInterface 暴露方法给 dom-extract.js 回调。
#     JS 侧是按**方法名字符串**调的；R8 不知道，改了名字 ⇒ 页面注入成功但永远
#     收不到 DOM，症状是「Agent 一直看不见页面」，极难归因。
# ───────────────────────────────────────────────────────────────────────────
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.ake.browser.DomExtractBridge { *; }
-keep class com.ake.browser.DomExtractBridge$* { *; }
-keepclassmembers class com.ake.browser.** {
    public *;
}

# ───────────────────────────────────────────────────────────────────────────
# 6. Hilt / Dagger
#     Hilt 的生成代码靠 @Inject / @AndroidEntryPoint 与注入点的**类名**绑定。
#     自带 consumer 规则已覆盖大部分，这里补 @AndroidEntryPoint 的入口类。
# ───────────────────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class dagger.** { *; }
-keep class com.ake.app.AkeApp { *; }
-keep class com.ake.app.**_HiltComponents.** { *; }
-keep class com.ake.app.**HiltModules.** { *; }
-keep class com.ake.ui.**_HiltComponents.** { *; }
-keep class com.ake.llm.**_HiltComponents.** { *; }
-keep class com.ake.browser.**_HiltComponents.** { *; }
-keep class com.ake.data.**_HiltComponents.** { *; }
-keep class com.ake.agent.**_HiltComponents.** { *; }
# ViewModel 由 hilt-navigation-compose 按类名反射创建
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }

# ───────────────────────────────────────────────────────────────────────────
# 7. Compose
#     Compose runtime/material3 自带规则；这里只有两条要补：
#     ① @Composable 函数被重组调度器按名字调用
#     ② 预览/工具链相关类（debug 才会用到，release 里直接丢）
# ───────────────────────────────────────────────────────────────────────────
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**
# 不要把 Composable 的 $lambda 内联后剩下的桥接方法删掉
-keepclassmembers class **$* {
    synthetic <methods>;
}

# ───────────────────────────────────────────────────────────────────────────
# 8. 序列化/协议边界：TaskEvent / TerminalReason 等 sealed 类型
#     :agent 的状态机事件被 :ui 按类型分支消费，也落库成字符串。
#     sealed 子类的**类名**就是判别式的载体，不能混淆。
# ───────────────────────────────────────────────────────────────────────────
-keep class com.ake.core.contract.** { *; }
-keep class com.ake.core.modelapi.** { *; }
-keepclassmembers enum com.ake.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ───────────────────────────────────────────────────────────────────────────
# 9. 显式不需要保留（体积优化）
# ───────────────────────────────────────────────────────────────────────────
# Compose tooling 只在 debug 预览里用，release 里删掉
-assumenosideeffects class androidx.compose.ui.tooling.** { *; }
# 遥测/分析类库本项目一个都没有引入，这里只是防止将来误引
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# ───────────────────────────────────────────────────────────────────────────
# 10. 端侧模型相关（重要）
#     模型文件不随包分发，包内不应出现任何权重。
#     若将来有人把 .task/.litertlm 放进 assets，这条规则会让它保持不压缩，
#     但也请先看 docs/03 §6 —— 那会让 APK 变成 2.6GB，是红线。
# ───────────────────────────────────────────────────────────────────────────
# 不加 -dontobfuscate：包名与模型无关，但权重文件名不应被改
# （当前无 assets 权重，保留注释作为将来的人工检查点）
