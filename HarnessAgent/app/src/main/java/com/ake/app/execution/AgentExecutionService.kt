package com.ake.app.execution

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.ake.app.AkeNotifications
import com.ake.core.common.AkeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 端侧推理保活服务。
 *
 * ### 为什么必须有它
 *
 * 端侧 Agent 一次任务是「8 步 × 每步 4.5 秒 ≈ 36 秒」的量级，且全程占用一个满载的核心。
 * Android 的后台限制会让非前台进程在被切走后很快被降级 / 被 LMK 回收，
 * 而**被回收的不是我们的 Service，往往是 WebView** —— 任务直接死在半路且无法恢复。
 *
 * 前台服务 + 常驻通知在这里的实际作用是：给用户一个**随时可中止**的入口。
 * 端侧推理不可打断会让「误操作」变成「必须等 30 秒」，这是体验红线。
 */
public class AgentExecutionService : Service() {

    @Inject
    internal lateinit var notifications: AkeNotifications

    private val job = SupervisorJob()
    private lateinit var scope: CoroutineScope

    private val _status = MutableStateFlow("IDLE")
    public val status: StateFlow<String> get() = _status.asStateFlow()

    public inner class LocalBinder : Binder() {
        public fun service(): AgentExecutionService = this@AgentExecutionService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(job + kotlinx.coroutines.Dispatchers.Default)
        notifications.ensureChannels()
        startInForeground()
    }

    private fun startInForeground() {
        val n = notifications.executionNotification(
            title = "正在执行任务",
            text = "端侧推理中…",
            indeterminate = true,
        )
            .addAction(0, "停止", stopPendingIntent())
            .build()
        ServiceCompat.startForeground(this, AkeNotifications.Ids.EXECUTION, n, FOREGROUND_TYPE)
    }

    public fun updateProgress(text: String, pct: Int): Unit {
        val n = notifications.executionNotification(
            title = "正在执行任务",
            text = text,
            indeterminate = false,
            progress = pct,
        )
            .addAction(0, "停止", stopPendingIntent())
            .build()
        notificationManager().notify(AkeNotifications.Ids.EXECUTION, n)
    }

    private fun notificationManager(): android.app.NotificationManager =
        getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager

    private fun stopPendingIntent(): android.app.PendingIntent {
        val i = Intent(this, AgentExecutionService::class.java).setAction(ACTION_STOP)
        return android.app.PendingIntent.getService(
            this,
            0,
            i,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AkeLog.i(TAG, "stop requested")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        job.cancel("service destroyed")
        super.onDestroy()
    }

    public companion object {
        public const val ACTION_STOP: String = "com.ake.app.action.STOP"

        private const val TAG = "AgentExecutionService"

        /** 与 Manifest 里 `foregroundServiceType` 保持一致（API 34+ 才生效）。 */
        private const val FOREGROUND_TYPE: Int =
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    }
}
