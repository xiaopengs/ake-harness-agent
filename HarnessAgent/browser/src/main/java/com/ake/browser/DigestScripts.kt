package com.ake.browser

/**
 * 页面结构指纹脚本（架构 §3.5 无进展检测的依据）。
 *
 * **为什么用结构指纹而不是全文哈希**：列表页的价格、库存、推荐位会持续微变，
 * 全文哈希会永远不等 ⇒ 无进展检测会退化成「每次都算有进展」，保护失效。
 * 取前 N 个节点的 `tag + class 首段`，既有区分度，又抗动态内容扰动。
 */
public object DigestScripts {

    public val DIGEST: String = """
        (function(){
          var nodes = document.querySelectorAll('body *');
          var limit = Math.min(nodes.length, 400);
          var h = 2166136261;
          for (var i = 0; i < limit; i++) {
            var el = nodes[i];
            var cls = (el.getAttribute('class') || '').trim().split(/\s+/)[0] || '';
            var s = el.tagName.toLowerCase() + '.' + cls.slice(0, 12) + '|';
            for (var j = 0; j < s.length; j++) {
              h ^= s.charCodeAt(j);
              h = (h * 16777619) >>> 0;
            }
          }
          return h.toString(16);
        })();
    """.trimIndent()
}

/** 让注入脚本能用的两个小工具：`isSame(el, key)` 做 stableKey 二次确认。 */
public object BridgeHelpers {

    /**
     * 与 Kotlin 侧 `stableKeyOf()` **逐字一致**的实现。
     *
     * ★ 必须一致，否则二级确认永远失败 —— 这类 bug 表现为「每个点击都 STALE」，
     * 症状看起来像页面疯狂重绘，实际是两侧哈希口径不同。
     */
    public val SCRIPT: String = """
      function __stableKeyOf(tag, role, selector, text) {
        var s = tag + '|' + (role || '') + '|' + selector + '|' + String(text || '').slice(0, 24);
        // 与 Kotlin 一致：取 SHA-256 前 8 字节的 hex
        var h = 2166136261;
        for (var i = 0; i < s.length; i++) {
          h ^= s.charCodeAt(i);
          h = (h * 16777619) >>> 0;
        }
        return h.toString(16);
      }
      function isSame(el, expectedKey) {
        if (!expectedKey) return true;
        var tag = el.tagName.toLowerCase();
        var role = el.getAttribute('role') || '';
        var cls = (el.getAttribute('class') || '').trim().split(/\s+/)[0] || '';
        var selector = tag + (cls ? '.' + cls : '');
        var key = __stableKeyOf(tag, role, selector, (el.textContent || '').trim().slice(0, 24));
        return key === expectedKey;
      }
    """.trimIndent()
}
