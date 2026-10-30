package com.ake.ui.model

import com.ake.core.ports.CloudFailure
import com.ake.core.ports.EgressPhase
import com.ake.ui.testsupport.UiStrings
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * # `CloudFailureCopy.kt` 的守卫
 *
 * ## 这一组测试对着 §4.4 那张表的一行或一个单元逐个写
 *
 * 这一文件盯的是三条判据，每一条都能被单点改动打红：
 *
 *  ① **PRE / POST 不许共用「有没有出去」的说法** —— 一旦共用，必有一半是假的。
 *     PRE ⇒ 允许回落，但必须说出 [PHRASE_FELL_BACK_TO_DEVICE]；
 *     POST ⇒ 禁止回落，必须说出 [PHRASE_ALREADY_SENT] + 归一化 host。
 *
 *  ② **互异**：17 种失败形态落到 17 份不同的文案。
 *     两份不同形态共用一句话时，那句必然对其中一半是假的 —— 与 `ChatCopyTest` 同一条。
 *
 *  ③ **穷尽性**：新加一种失败形态时，本文件与 `cloudFailureCopyOf` 的 `when` 同时红 ——
 *     后者靠 Kotlin 的穷尽性，前者靠本文件的形态清单[ALL]。
 */
class CloudFailureCopyTest {

    private companion object {
        /** 每一个失败形态，穷尽列出 —— 落一个就编译失败。 */
        val ALL: List<CloudFailure> = listOf(
            CloudFailure.NoPermit,
            CloudFailure.CredentialMissing,
            CloudFailure.ConnectFailed,
            CloudFailure.AuthRejected,
            CloudFailure.ProxyAuthRequired,
            CloudFailure.InsecureEndpoint,
            CloudFailure.PageContextRefused,
            CloudFailure.ModelNotFound,
            CloudFailure.ContextTooLong,
            CloudFailure.RateLimited,
            CloudFailure.ServerError,
            CloudFailure.FirstByteTimeout,
            CloudFailure.StreamIdleTimeout,
            CloudFailure.StreamTruncated,
            CloudFailure.DeadlineExceeded,
            CloudFailure.EmptyDelta,
            CloudFailure.MalformedResponse,
        )

        const val HOST: String = "gw.internal.example.com"
    }

    // ══════════════════════════════════════════════ ① 字面文本的前提

    @Test
    fun theStringReaderReallyReadsRealCopy() {
        // ★ 下面所有「看字面文本」的断言都建立在这条上，和 `ChatCopyTest` 同一手法。
        assertThat(UiStrings.entries.size).isAtLeast(100)
        assertThat(UiStrings.named("err_cloud_auth_rejected_t")).isNotEmpty()
        assertThat(UiStrings.named("err_cloud_model_not_found_n")).contains("已经发给")
    }

    // ══════════════════════════════════════════════ ② PRE / POST 分水岭

    /**
     * ★ 本轮最重要的一条。
     *
     * PRE 的事实是「一个字节都没发出去」。允许回落到本机，因此 **必须** 说出回落了；
     * 反过来它没有任何资格说「已经发给谁」—— 那是一次没发生的出境。
     */
    @Test
    fun preFailuresSayFellBackToDeviceAndNeverSayAlreadySent() {
        val pre = ALL.filter { it.phase == EgressPhase.PRE }

        assertWithMessage("PRE 组一条都不剩的时候，这条断言会变成永真 ——那正是它要防的事")
            .that(pre).isNotEmpty()

        pre.forEach { failure ->
            val copy = cloudFailureCopyOf(failure)
            assertWithMessage("${failure.code}.phase").that(copy.phase).isEqualTo(EgressPhase.PRE)

            val spoken = UiStrings.text(copy.titleRes) + UiStrings.text(copy.nextRes)
            assertWithMessage(
                "${failure.code} 是 PRE：允许回落，必须说出「${PHRASE_FELL_BACK_TO_DEVICE}」。" +
                    "\n  实际：($spoken)"
            ).that(spoken).contains(PHRASE_FELL_BACK_TO_DEVICE)

            assertWithMessage(
                "${failure.code} 是 PRE：一个字节都没发出去，不许说「${PHRASE_ALREADY_SENT}」。"
            ).that(spoken).doesNotContain(PHRASE_ALREADY_SENT)
        }
    }

    /**
     * ★ POST 的事实是「请求体已经发出」。禁止回落，且必须把发给谁说出口。
     *
     * 同时禁止出现「已改由本机模型回答」—— 说得好像系统拿本机去补了这一段，
     * 那正是 §4.4 明写的「云端空了就用本地补一段」那种静默降级的变体。
     */
    @Test
    fun postFailuresSayAlreadySentToTheNormalisedHostAndNeverSayFellBack() {
        val post = ALL.filter { it.phase == EgressPhase.POST }

        assertWithMessage("POST 组一条都不剩的时候，这条断言会变成永真")
            .that(post).isNotEmpty()

        post.forEach { failure ->
            val copy = cloudFailureCopyOf(failure)
            assertWithMessage("${failure.code}.phase").that(copy.phase).isEqualTo(EgressPhase.POST)

            // ★ 走与界面同一条插值路径：各自格式化一次，就等于把渲染排除在测试之外。
            val next = formatCloudCopy(UiStrings.text(copy.nextRes), HOST)
            assertWithMessage(
                "${failure.code} 是 POST：必须说出「${PHRASE_ALREADY_SENT} $HOST」（§4.4 总原则）。" +
                    "\n  实际：($next)"
            ).that(next).contains("$PHRASE_ALREADY_SENT $HOST")

            assertWithMessage(
                "${failure.code} 是 POST：禁止回落，不许出现「${PHRASE_FELL_BACK_TO_DEVICE}」。"
            ).that(next).doesNotContain(PHRASE_FELL_BACK_TO_DEVICE)
        }
    }

    /**
     * ★ 最容易写错的一档单独钉一次：`FirstByteTimeout` 名字里带「超时」，
     * 极易被当成「没连上」而回落到本机 —— 那一刻用户的整段历史已经在对方服务器上了。
     */
    @Test
    fun firstByteTimeoutIsPostNotPre() {
        val copy = cloudFailureCopyOf(CloudFailure.FirstByteTimeout)

        assertThat(copy.phase).isEqualTo(EgressPhase.POST)
        assertThat(CloudFailure.FirstByteTimeout.phase).isEqualTo(EgressPhase.POST)
        assertThat(formatCloudCopy(UiStrings.text(copy.nextRes), HOST))
            .contains("$PHRASE_ALREADY_SENT $HOST")
    }

    /** 锁 C 的产物单独钉一次：DOM 红线在文案上的模样。 */
    @Test
    fun pageContextRefusedSaysItWasHandledOnDevice() {
        val copy = cloudFailureCopyOf(CloudFailure.PageContextRefused)

        assertThat(copy.phase).isEqualTo(EgressPhase.PRE)
        assertThat(UiStrings.text(copy.titleRes)).contains("网页")
        assertThat(UiStrings.text(copy.nextRes)).contains(PHRASE_FELL_BACK_TO_DEVICE)
        assertThat(UiStrings.text(copy.nextRes)).doesNotContain(PHRASE_ALREADY_SENT)
    }

    // ══════════════════════════════════════════════ ③ 互异性

    @Test
    fun everyFailureMapsToItsOwnCopy() {
        val copies = ALL.associateWith { cloudFailureCopyOf(it) }

        assertThat(copies).hasSize(ALL.size)

        val sharedTitles = copies.entries.groupBy({ it.value.titleRes }, { it.key })
            .filterValues { it.size > 1 }
        val sharedNexts = copies.entries.groupBy({ it.value.nextRes }, { it.key })
            .filterValues { it.size > 1 }

        assertWithMessage(
            "有两种失败形态共用同一句标题 —— 那句必然对其中一半是假的。"
        ).that(sharedTitles).isEmpty()
        assertWithMessage("有两种失败形态共用同一句「下一步」").that(sharedNexts).isEmpty()
    }

    @Test
    fun theCopyTableCoversEveryFailureVariant() {
        // ★ 新增第 18 种形态时这里必须手动补进 [ALL] ⇒ 这条先红，
        //   然后才是 `cloudFailureCopyOf` 的穷尽 `when` 编译失败。两道都不该省。
        assertThat(ALL.distinct()).hasSize(ALL.size)

        // ★ 不用 kotlin-reflect（单测 classpath 里没有它的实现），走 Java 反射数嵌套子类型：
        //   `CloudFailure` 的每个变体都是它 body 里的一个嵌套类，加一个这里就多一个。
        val declared = CloudFailure::class.java.declaredClasses.map { it.simpleName }.toSet()
        val listed = ALL.map { it::class.java.simpleName }.toSet()
        assertWithMessage(
            "表里列了 ${listed.size} 种（$listed），而 `CloudFailure` 实际有 ${declared.size} 种（$declared）" +
                " —— 新增形态没人写文案就会悄悄落到某个兜底分支。"
        ).that(declared).isEqualTo(listed)
    }

    @Test
    fun everyCopyActuallySaysSomething() {
        ALL.forEach { failure ->
            val copy = cloudFailureCopyOf(failure)
            assertWithMessage("${failure.code} 标题为空").that(UiStrings.text(copy.titleRes)).isNotEmpty()
            assertWithMessage("${failure.code} 缺下一步").that(UiStrings.text(copy.nextRes)).isNotEmpty()
        }
    }

    // ══════════════════════════════════════════════ ④ §6.3：文案里装不下秘密

    /** `CloudFailure` 形状上就没有 message / cause / body / url —— 于是文案里也拼不进去。 */
    @Test
    fun copyTemplatesCarryNoSecretFields() {
        CloudFailure::class.java.methods
            .filter { it.name.startsWith("get") || it.name.startsWith("component") }
            .map { it.name }
            .let { names ->
                assertWithMessage(
                    "`CloudFailure` 长出了可疑的读取口（${names.joinToString()}）" +
                        " —— 它会成为把服务端原文带上界面的通道。"
                ).that(names).containsNoneOf(
                    "getMessage", "getCause", "getBody", "getUrl", "getRequestLine",
                )
            }
    }
}
