package com.ake.app.di

import android.content.Context
import com.ake.core.ports.DocumentPort
import com.ake.document.PdfBoxDocumentExtractor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * 文档解析的实现绑定。
 *
 * ## 为什么在 `:app` 而不是 `:document` 自己 @Binds
 *
 * `:document` 只持有 PDFBox 这个外部 SDK，不该知道"缓存放哪" —— 那是 Android 上下文的事，
 * 而 `:document` 是**纯 JVM** 模块（见它的 build.gradle.kts：没有 android 插件）。
 * 把 Context 塞进 `:document` 会让它的 10 条单测全部需要 Robolectric。
 *
 * ## 为什么 scratch 目录单独 mkdirs
 *
 * `PDFBox` 的 `MemoryUsageSetting.setTempDir` **不会**帮你建目录，目录不存在时
 * 它在解析大文档（超过 24MB 阈值）的那一刻才抛 `FileNotFoundException`。
 * 那个时间点用户已经等了十几秒 —— 建目录的成本是 0，所以在这里建。
 */
@Module
@InstallIn(SingletonComponent::class)
public object DocumentModule {

    @Provides
    @Singleton
    public fun provideDocumentPort(@ApplicationContext context: Context): DocumentPort {
        val scratch = File(context.cacheDir, "pdf-scratch").apply { mkdirs() }
        return PdfBoxDocumentExtractor(cacheDir = scratch)
    }
}
