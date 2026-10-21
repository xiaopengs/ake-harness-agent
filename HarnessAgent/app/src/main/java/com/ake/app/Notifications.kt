package com.ake.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知通道与通知构造器。
 *
 * 三个通道是有意为之：
 *  - `execution`   —— 任务执行中（常驻、低优先级、不可滑动）
 *  - `human_gate`  —— 需要用户裁决（**高优先级、带按钮**）
 *  - `download`    —— 模型下载进度
 *
 * 分开的理由：人机交互点必须能打断用户，执行状态则不该打扰用户 ——
 * 混在一个通道里就调不出「一个不响、一个响」的效果。
 */
@Singleton
public class AkeNotifications @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {

    public object Channels {
        public const val EXECUTION: String = "ake.execution"
        public const val HUMAN_GATE: String = "ake.human_gate"
        public const val DOWNLOAD: String = "ake.download"
    }

    public object Ids {
        public const val EXECUTION: Int = 1001
        public const val HUMAN_GATE: Int = 2000
        public const val DOWNLOAD: Int = 3001
    }

    public fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = appContext.getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(Channels.EXECUTION, "任务执行", NotificationManager.IMPORTANCE_LOW).apply {
                description = "端侧 Agent 正在执行任务"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(Channels.HUMAN_GATE, "需要确认", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "敏感操作需要你确认后才会继续"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(Channels.DOWNLOAD, "模型下载", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Gemma 4 模型文件下载进度"
            },
        )
    }

    public fun executionNotification(
        title: String,
        text: String,
        indeterminate: Boolean = true,
        progress: Int = 0,
    ): NotificationCompat.Builder = NotificationCompat.Builder(appContext, Channels.EXECUTION)
        .setSmallIcon(R.drawable.ic_stat_agent)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(mainIntent())
        .apply {
            if (indeterminate) {
                setProgress(0, 0, true)
            } else {
                setProgress(100, progress.coerceIn(0, 100), false)
            }
        }

    public fun downloadNotification(
        downloadedBytes: Long,
        totalBytes: Long,
        done: Boolean = false,
    ): NotificationCompat.Builder {
        val pct = if (totalBytes > 0) ((downloadedBytes * 100L) / totalBytes).toInt() else 0
        val text = if (done) {
            "Gemma 4 已下载完成并校验通过"
        } else {
            "${downloadedBytes / 1_048_576} MB / ${totalBytes / 1_048_576} MB"
        }
        return NotificationCompat.Builder(appContext, Channels.DOWNLOAD)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (done) "模型已就绪" else "正在下载模型")
            .setContentText(text)
            .setOngoing(!done)
            .setOnlyAlertOnce(true)
            .setProgress(100, if (done) 0 else pct.coerceIn(0, 100), false)
            .setContentIntent(mainIntent())
    }

    public fun humanGateNotification(
        id: Int,
        title: String,
        text: String,
        actions: List<NotificationCompat.Action>,
    ): NotificationCompat.Builder = NotificationCompat.Builder(appContext, Channels.HUMAN_GATE)
        .setSmallIcon(R.drawable.ic_stat_gate)
        .setContentTitle(title)
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setAutoCancel(true)
        .setContentIntent(mainIntent())
        .apply { actions.forEach { addAction(it) } }

    public fun notify(id: Int, builder: NotificationCompat.Builder) {
        val nm = appContext.getSystemService(NotificationManager::class.java)
        nm.notify(id, builder.build())
    }

    /** 点通知回到任务面板。 */
    private fun mainIntent(): PendingIntent {
        val i = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            0,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
