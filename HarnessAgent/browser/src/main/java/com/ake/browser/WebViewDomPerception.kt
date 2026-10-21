package com.ake.browser

import android.webkit.WebView
import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.contract.PocketDom
import com.ake.core.contract.RecordTable
import com.ake.core.contract.UiElement
import com.ake.core.ports.DomPerceptionPort
import com.ake.core.ports.ExtractHint
import com.ake.core.ports.ObserveOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `DomPerceptionPort` 的 WebView 实现（架构 §2.2 / §5）。
 *
 * ★ **同层不依赖（R2）**：这个类**不去 import 执行器的实现**，
 * 它只吃 [ObserveOptions] / [ExtractHint]，产出 [PocketDom] / [RecordTable]。
 * 「当前是哪张网页」由 L4 配对（执行器 `open()` → 感知 `observe()`）。
 * 这是本项目处理「两个能力必须协作」的通用解法。
 */
public class WebViewDomPerception(
    public val webView: WebView,
    /**
     * ★ 由 [BrowserRuntime] 注入、与 [WebViewExecutor] **共用**的 bridge。
     * 理由同 `WebViewExecutor` 的构造函数注释：`addJavascriptInterface` 按名字覆盖。
     */
    private val bridge: DomExtractBridge,
) : DomPerceptionPort {

    // （JS 接口的注入/摘除统一在 BrowserRuntime 做，这里不再持有 ——
    //  感知侧自己 detach 会把执行侧正在用的 bridge 一起掐掉。）

    override suspend fun observe(opts: ObserveOptions): Outcome<PocketDom> {
        val json = bridge.run(observeJs(opts), timeoutMs = PERCEIVE_TIMEOUT_MS)
            ?: return Outcome.Err(AppError(AppError.CODE_TIMEOUT, "err_perceive_timeout"))

        return try {
            Outcome.Ok(DomJsonParser.parse(json, topK = opts.candidateTopK))
        } catch (t: Throwable) {
            Outcome.Err(AppError(AppError.CODE_UNKNOWN, "err_perceive_parse_failed", t))
        }
    }

    override suspend fun extractRecords(hint: ExtractHint): Outcome<RecordTable> {
        val js = """
            (function(){
              var roots = document.querySelectorAll(${hint.selectorHint.js()});
              var fields = ${hint.fields.joinToString(",", "[", "]") { it.js() }}.map(function(x){return x;});
              var limit = ${hint.limit.coerceIn(1, 100)};
              var cols = [];
              if (fields.length) { cols = fields; }
              else {
                var seen = {};
                for (var r = 0; r < Math.min(roots.length, 3); r++) {
                  var kids = roots[r].children;
                  for (var ci = 0; ci < kids.length && cols.length < 6; ci++) {
                    var n = (kids[ci].getAttribute('class') || '').trim().split(/\s+/)[0];
                    if (!n) n = kids[ci].tagName.toLowerCase();
                    if (!seen[n]) { seen[n] = 1; cols.push(n); }
                  }
                }
              }
              var rows = [];
              for (var i = 0; i < Math.min(roots.length, limit); i++) {
                var row = {};
                for (var c = 0; c < cols.length; c++) {
                  var kids = roots[i].children;
                  var hit = null;
                  for (var k = 0; k < kids.length; k++) {
                    var n = (kids[k].getAttribute('class') || '').trim().split(/\s+/)[0];
                    if (!n) n = kids[k].tagName.toLowerCase();
                    if (n === cols[c]) { hit = kids[k]; break; }
                  }
                  row[cols[c]] = hit
                    ? (hit.textContent || '').replace(/\s+/g,' ').trim().slice(0,40)
                    : '';
                }
                rows.push(row);
              }
              var result = {
                schemaHint: ${hint.selectorHint.js()},
                columns: cols,
                rows: rows,
                totalEstimate: roots.length,
                truncated: roots.length > rows.length
              };
              AkeBridge.deliver(AKE_CB_ID, JSON.stringify(result));
            })();
        """.trimIndent()

        val raw = bridge.run(js, timeoutMs = PERCEIVE_TIMEOUT_MS)
            ?: return Outcome.Err(AppError(AppError.CODE_TIMEOUT, "err_extract_timeout"))

        return try {
            val table = DomJsonParser.parseRecords(org.json.JSONObject(raw))
            if (table == null) {
                Outcome.Err(AppError(AppError.CODE_UNKNOWN, "err_extract_no_columns"))
            } else {
                Outcome.Ok(table)
            }
        } catch (t: Throwable) {
            Outcome.Err(AppError(AppError.CODE_UNKNOWN, "err_extract_parse_failed", t))
        }
    }

    private fun observeJs(opts: ObserveOptions): String = """
        (function(){
          var OPTS = {
            maxTextLen: ${UiElement.MAX_TEXT_LEN},
            viewportExpansion: ${opts.viewportExpansionPx},
            includeBelowFold: ${opts.includeTextBelowFold},
            maxRecords: ${opts.maxRecordsHint},
            maxNodes: 20000,
            maxDepth: 40
          };
          var result = __extract(OPTS);
          return JSON.stringify(result);
        })();
    """.trimIndent()

    // ★ `const val` 只能出现在 top level / object / companion object ——
    //   直接写在 class 体里会报 "Const 'val' is only allowed on top level..."。
    private companion object {
        const val TAG = "WebViewDomPerception"
        const val PERCEIVE_TIMEOUT_MS = 3_000L
    }
}
