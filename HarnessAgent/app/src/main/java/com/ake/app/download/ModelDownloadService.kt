package com.ake.app.download

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.ake.app.AkeNotifications
import com.ake.core.common.AkeLog
import com.ake.core.common.Outcome
import com.ake.llm.ModelDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 模型下载前台服务（架构 §6.4）。
 *
 * ### 为什么不用 WorkManager
 * 两者都能后台继续，但这里选前台服务：
 *  - **用户可感知**：2.6 GB 的下载必须常驻可见、随时可取消。别让用户以为卡死。
 *  - **路径要求**：`DownloadManager` 只能写 `getExternalFilesDir()`，写不了内部存储；
 *    而内部存储对本项目的安全模型更合适（模型文件同样是不该被别的 App 顺手读到的资产）。
 *  - WorkManager 仍然保留在依赖里，供「清理作业 / 保留策略」这类真正的后台任务使用。
 */
public class ModelDownloadService : Service() {

    @Inject
    internal lateinit var downloader: ModelDownloader

    @Inject
    internal lateinit var notifications: AkeNotifications

    private val job = SupervisorJob()
    private lateinit var scope: CoroutineScope

    public inner class LocalBinder : Binder() {
        public fun service(): ModelDownloadService = this@Service
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(job + Dispatchers.Default)
        notifications.ensureChannels()
        startForeground(
            AkeNotifications.Ids.DOWNLOAD,
            notifications.downloadNotification(0L, 0L).build(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID)
        if (modelId != null) start(modelId)
        return START_STICKY
    }

    private fun start(modelId: String) {
        scope.launch {
            val outcome = downloader.download(
                req = ModelDownloader.Request(
                    id = modelId,
                    remoteUrl = DEFAULT_MODEL_URL,
                    expectedBytes = DEFAULT_MODEL_BYTES,
                    sha256 = DEFAULT_MODEL_SHA256,
                ),
                onProgress = { p ->
                    when (p) {
                        is ModelDownloader.Progress.Running ->
                            notifications.downloadNotification(p.downloadedBytes, p.totalBytes)

                        is ModelDownloader.Progress.Verifying ->
                            notifications.downloadNotification(p.downloadedBytes, p.downloadedBytes)

                        ModelDownloader.Progress.Done ->
                            notifications.downloadNotification(0L, 0L, done = true)
                    }.let { /* 通知由下面的统一出口发出 */ }

                    val n = when (p) {
                        is ModelDownloader.Progress.Running ->
                            notifications.downloadNotification(p.downloadedBytes, p.totalBytes)
                        is ModelDownloader.Progress.Verifying ->
                            notifications.downloadNotification(p.downloadedBytes, p.downloadedBytes)
                        ModelDownloader.Progress.Done ->
                            notifications.downloadNotification(0L, 0L, done = true)
                    }
                    notify(n.build())
                },
            )
            when (outcome) {
                is Outcome.Ok -> AkeLog.i(TAG, "download ok: ${outcome.value.absolutePath}")
                is Outcome.Err -> AkeLog.e(TAG, "download failed: ${outcome.error.code}", outcome.error.cause)
            }
            stopSelf(startId)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        job.cancel("download service destroyed")
        super.onDestroy()
    }

    public companion object {
        public const val EXTRA_MODEL_ID: String = "extra.model_id"

        // ★ 上线前必须替换成「我方签名过的 manifest」里的真实值。
        //   保留占位常量是刻意的 fail-closed：真值缺失 ⇒ sha256 校验失败 ⇒ 绝不会加载一个来源不明的大文件。
        public const val DEFAULT_MODEL_URL: String =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/model.litertlm"
        public const val DEFAULT_MODEL_BYTES: Long = 2_770_000_000L   // ≈2.58 GB
        public const val DEFAULT_MODEL_SHA256: String = "REPLACE_WITH_SIGNED_MANIFEST_SHA256"
    }
}
