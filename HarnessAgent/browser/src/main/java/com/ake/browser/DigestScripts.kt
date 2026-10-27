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

/**
 * `stableKey` 的**哈希常量注入段**。
 *
 * ★★ 这是历史上「点击全废」的根因所在位置。修复前这里是一份手写的 FNV-1a 实现
 * （`__stableKeyOf` + `isSame`），而它**从未被注入到任何 WebView** ——
 * Kotlin 侧 `stableKeyOf` 用的是 SHA-256，JS 侧这份用的是 FNV-1a，两边连算法都不是一个，
 * 于是 `isSame()` 恒 false ⇒ click/type/select 一律 'STALE'。
 *
 * 现在的分工：
 *   · **算法只有一份**，写在 `dom-extract.js` 里（`__stableKeyOf` / `isSame`）——
 *     它和抽取用的 `compactText` 同处一个闭包，文本归一口径不可能漂；
 *   · **常量只有一份**，就是 `DomJsonParser` 的这三个 `const val`，由本段注入成全局变量。
 *
 * 于是两端既不可能「算法不同」，也不可能「常量不同」—— 剩下的只有脚本体本身，
 * 而它是 10 行算术，可以逐字核对。
 *
 * 注入顺序：**必须在 `dom-extract.js` 之前**（见 `JsBootstrap.RESOURCES`）。
 */
public object BridgeHelpers {

    public val SCRIPT: String = """
      // stableKey 哈希常量 —— 唯一数据源是 Kotlin 的 DomJsonParser；dom-extract.js 只读取。
      var AKE_FNV_PRIME = ${DomJsonParser.FNV_PRIME};
      var AKE_FNV_OFFSET_A = ${DomJsonParser.FNV_OFFSET_A};
      var AKE_FNV_OFFSET_B = ${DomJsonParser.FNV_OFFSET_B};
      var AKE_STABLE_KEY_LEN = ${DomJsonParser.STABLE_KEY_LEN};
    """.trimIndent()
}
