package com.ake.app.download

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.ake.app.AkeNotifications
import com.ake.core.common.AkeLog
import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import com.ake.core.modelapi.ModelErrorCodes
import com.ake.harnessagent.BuildConfig
import com.ake.llm.ModelCatalog
import com.ake.llm.ModelDistributionSync
import com.ake.llm.ModelDownloader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
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
// ★ `@AndroidEntryPoint` 不是可选项：本类有 `@Inject` 字段，缺了它 Hilt 不会注入，
//   运行期第一次碰 `downloader` 就是 `lateinit property has not been initialized` ——
//   而且是在 download 这种长耗时路径上才炸，很难往回查。
//
// ★★ **本类是分发链路在生产侧的调用方**（R8 视角的「可达性起点」）：
//    2026-10 上轮实测 `ModelDirInventory` / `ModelDistributionSync` 一起进了 `usage.txt`，
//    根因就是这里当时直连 `ModelDownloader.download` ⇒ 整条链没有可达调用方。
//    加 keep 规则是错的修法（类留在包里但没人调用 = 假装通电），所以这里改成调
//    `sync.ensureCurrent()`：manifest → 决议 → 账本裁决 → 下载，一步都不少。
@AndroidEntryPoint
public class ModelDownloadService : Service() {

    @Inject
    internal lateinit var notifications: AkeNotifications

    /** 分发链路的唯一入口。**下载一律走它**，不要碰 `ModelDownloader.download`。 */
    @Inject
    internal lateinit var sync: ModelDistributionSync

    /** 烧录目录项：manifest 尚未落地时的兜底（见 [fallbackToCatalog]）。 */
    @Inject
    internal lateinit var catalog: ModelCatalog

    private val job = SupervisorJob()
    private lateinit var scope: CoroutineScope

    public inner class LocalBinder : Binder() {
        // ⚠ 标签是外层**类名**，不是 `this@Service` —— 后者会被解析成 label 而报
        //   "Unresolved label"。Kotlin 的 qualified this 一律写 `this@OuterClassName`。
        public fun service(): ModelDownloadService = this@ModelDownloadService
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
        if (modelId != null) start(modelId, startId)
        return START_STICKY
    }

    /**
     * @param startId **必须透传进来**：下载是异步的，`stopSelf()` 会立刻停掉服务，
     *                而 `stopSelf(startId)` 只在「这次 start 还没被后续 start 取代」时才停 ——
     *                否则中途又来一个下载请求会被误停。
     */
    private fun start(modelId: String, startId: Int) {
        scope.launch {
            // ⚠ `runCatching` 会把 `CancellationException` 也吞掉 —— 那等于「服务已销毁
            //   但下载协程还在跑」（`job.cancel()` 失去作用）。取消必须继续抛出去。
            val outcome = try {
                download(modelId)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Outcome.Err(
                    AppError(
                        ModelErrorCodes.DOWNLOAD_FAILED,
                        "err_model_download_failed",
                        t,
                        Severity.HIGH,
                    ),
                )
            }
            when (outcome) {
                is Outcome.Ok -> AkeLog.i(TAG, "download ok: ${outcome.value.absolutePath}")
                is Outcome.Err -> AkeLog.e(TAG, "download failed: ${outcome.error.code}", outcome.error.cause)
            }
            stopSelf(startId)
        }
    }

    /**
     * 走分发链路拿一个可加载的模型文件。
     *
     * ## 两步：先问 manifest，再决定退不退回烧录目录项
     *
     * `sync.ensureCurrent()` 在 manifest 不可用时**原样报错**（fail-closed，
     * 默认配置 `UNCONFIGURED` 就一定走到这里）。是否退回烧录项是**本服务的产品决策**，
     * 所以放在这里，并且**必须留一条 warn** —— 否则「分发链路到底通没通」在真机上
     * 永远看不出来（不报错、不告警，只是悄悄走了老路）。
     */
    private suspend fun download(modelId: String): Outcome<File> {
        val onProgress: (ModelDownloader.Progress) -> Unit = { p ->
            val n = when (p) {
                is ModelDownloader.Progress.Running ->
                    notifications.downloadNotification(p.downloadedBytes, p.totalBytes)
                is ModelDownloader.Progress.Verifying ->
                    notifications.downloadNotification(p.downloadedBytes, p.downloadedBytes)
                ModelDownloader.Progress.Done ->
                    notifications.downloadNotification(0L, 0L, done = true)
            }
            notify(n.build())
        }

        val resolved = sync.ensureCurrent(appVersionCode = BuildConfig.VERSION_CODE, onProgress = onProgress)
        if (resolved is Outcome.Ok) return resolved
        val code = (resolved as Outcome.Err).error.code

        // ★ 不是静默放行：错误码照实记，只是「还可以用老办法」而已。
        AkeLog.w(TAG, "manifest 不可用（$code）⇒ 沿用烧录目录项（上线前不能长期停留在这个状态）")
        return fallbackToCatalog(modelId, onProgress)
    }

    /**
     * 兜底：把 APK 内烧录的目录项喂给**同一条账本链路**（[ModelDistributionSync.ensure]）。
     *
     * ⚠ 这里仍然**不**直连 `ModelDownloader.download`：即便走老数据，归档旧模型、
     * 进度落库、校验裁决这三件事照样得发生 —— 少了它们，「老路径」会在真机上悄悄
     * 覆盖用户已装的可用模型（硬规则 1）。
     */
    private suspend fun fallbackToCatalog(
        modelId: String,
        onProgress: (ModelDownloader.Progress) -> Unit,
    ): Outcome<File> {
        val record = catalog.find(modelId)
            ?: return Outcome.Err(
                AppError(
                    ModelErrorCodes.LOAD_FAILED,
                    "err_model_unknown_id",
                    null,
                    Severity.HIGH,
                ),
            )
        return sync.ensure(catalog.manifestEntryOf(record), onProgress)
    }

    /** 更新常驻通知。走 `NotificationManagerCompat` 才会带上所需的 POST_NOTIFICATIONS 兼容处理。 */
    private fun notify(builder: android.app.Notification) {
        androidx.core.app.NotificationManagerCompat.from(this).apply {
            runCatching { notify(AkeNotifications.Ids.DOWNLOAD, builder) }
                .onFailure { AkeLog.w(TAG, "notify failed", it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        job.cancel("download service destroyed")
        super.onDestroy()
    }

    public companion object {
        private const val TAG: String = "ModelDownloadService"

        public const val EXTRA_MODEL_ID: String = "extra.model_id"

        // ★ 原先这里有三个 `DEFAULT_MODEL_*` 占位常量（URL / 字节数 / sha256）。
        //   它们与 `ModelCatalog.GEMMA4_E2B_IT` 是同一份数据的两份拷贝 ——
        //   既然兜底路径改为「把烧录目录项喂给账本链路」，拷贝就必须只有一份，
        //   否则将来只改 `ModelCatalog` 里那一处时，这里会留下一个永远校验不过的旧值。
        //   fail-closed 的性质不变：`ModelCatalog` 的 sha256 仍是待替换的占位串 ⇒
        //   真值缺失时校验阶段必失败，绝不会加载来源不明的大文件。
    }
}
