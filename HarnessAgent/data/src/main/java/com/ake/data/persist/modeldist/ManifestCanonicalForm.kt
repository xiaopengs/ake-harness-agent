package com.ake.data.persist.modeldist

/**
 * manifest 的**规范化字节**（签名覆盖的对象）。
 *
 * ## 为什么签名不直接覆盖「下载到的原始字节」
 *
 * 原始字节方案要求服务端与客户端对**每一个空格**达成一致：CDN 回源压缩、镜像重写、
 * 中间任何一层做了 pretty-print，签名就全废。而失效的表现是「所有客户端同时拉不到模型」，
 * 且排查时看不出是签名坏了还是内容坏了。
 *
 * 规范化方案：双方各自把**解析后的字段**按固定顺序重新序列化，签的是这个结果。
 * 代价是服务端必须实现同一套规范化（约 40 行），收益是
 *  - 空白 / 键顺序 / 缩进完全无关；
 *  - 未知字段被丢弃 ⇒ 服务端加字段不会打断老客户端的验签（前向兼容）；
 *  - 单测可以**离线**构造签名并验证（见 `ManifestParserTest`），不需要真 CDN。
 *
 * ## 规范化规则（改动任何一条 = 线上全量验签失败，必须同时改服务端）
 *
 *  1. 不含 `signature` 字段本身（自己签自己是不可能的）；
 *  2. 键顺序固定为本文件写的顺序，**不**按字典序（字典序会随字段改名漂移）；
 *  3. `models` 按 `(model_id, variant)` 升序 —— manifest 里条目顺序不该影响签名；
 *  4. 字符串只转义 JSON 要求的 7 个字符，非 ASCII 直出 UTF-8；
 *  5. `max_app_version_code` 缺失与显式 `null` **等价**（都渲染成 `null`）。
 */
public object ManifestCanonicalForm {

    public fun render(manifest: ModelManifest): ByteArray {
        val sb = StringBuilder(512)
        sb.append('{')
        sb.append(key("schema_version")).append(':').append(manifest.schemaVersion).append(',')
        sb.append(key("manifest_version")).append(':').append(manifest.manifestVersion).append(',')
        sb.append(key("generated_at")).append(':').append(manifest.generatedAt).append(',')
        sb.append(key("models")).append(':').append('[')
        val sorted = manifest.models.sortedWith(compareBy({ it.modelId }, { it.variant }))
        sorted.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            sb.append(renderEntry(e))
        }
        sb.append(']')
        sb.append('}')
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun renderEntry(e: ModelManifestEntry): String {
        val sb = StringBuilder(256)
        sb.append('{')
        sb.append(key("model_id")).append(':').append(StrictJson.quote(e.modelId)).append(',')
        sb.append(key("variant")).append(':').append(StrictJson.quote(e.variant)).append(',')
        sb.append(key("revision")).append(':').append(e.revision).append(',')
        sb.append(key("version")).append(':').append(StrictJson.quote(e.version)).append(',')
        sb.append(key("url")).append(':').append(StrictJson.quote(e.url)).append(',')
        sb.append(key("size_bytes")).append(':').append(e.sizeBytes).append(',')
        sb.append(key("sha256")).append(':').append(StrictJson.quote(e.sha256)).append(',')
        sb.append(key("min_app_version_code")).append(':').append(e.minAppVersionCode).append(',')
        sb.append(key("max_app_version_code")).append(':')
            .append(e.maxAppVersionCode?.toString() ?: "null").append(',')
        sb.append(key("context_window")).append(':').append(e.contextWindow).append(',')
        sb.append(key("min_ram_gb")).append(':').append(e.minRamGb).append(',')
        sb.append(key("capabilities")).append(':').append('[')
        e.capabilities.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            sb.append(StrictJson.quote(c))
        }
        sb.append(']').append(',')
        sb.append(key("state")).append(':').append(StrictJson.quote(e.state.name))
        sb.append('}')
        return sb.toString()
    }

    private fun key(name: String): String = StrictJson.quote(name)
}
