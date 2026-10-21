package com.ake.app.di

import com.ake.app.device.AndroidDeviceHealth
import com.ake.app.security.AndroidKeystoreFieldCipher
import com.ake.core.common.FieldCipher
import com.ake.core.ports.DeviceHealthPort
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Android-only 能力的装配。
 *
 * 【归属说明 · 需仲裁】架构 §2.1 给 `DeviceHealthPort` 安排的实现是 `:platform:device`，
 * 给 Keystore 加密安排的是 `:data:persist`（数据库 §8.3）。当前工程只有 8 个模块、
 * 且 `:data` 归霍承钧，我不越界写它的实现，于是这两个 Android-only 实现**临时落在 `:app`**。
 *
 * 等 `:data:persist` 落地后，把 `AndroidKeystoreFieldCipher` 整个文件搬过去，
 * 装配点只要改一行 `@Binds` 的 impl 类型 —— 这正是「把 SDK 关在 Port 后面」的收益。
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class PlatformBindingsModule {

    /** 设备健康：内存档位 / 热状态 / 电量 / SoC 画像 */
    @Binds
    @Singleton
    public abstract fun bindDeviceHealth(impl: AndroidDeviceHealth): DeviceHealthPort

    /** R1 加密通道：Android Keystore + AES-256-GCM */
    @Binds
    @Singleton
    public abstract fun bindFieldCipher(impl: AndroidKeystoreFieldCipher): FieldCipher
}
