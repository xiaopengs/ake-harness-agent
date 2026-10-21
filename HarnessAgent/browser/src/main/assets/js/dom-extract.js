/**
 * dom-extract.js —— DOM → 紧凑 JSON 的第一段（也是砍得最狠的一段）。
 *
 * ★★ **为什么这一段必须写在 JS 里，而不是把整棵 DOM serialize 回 Kotlin**：
 *
 *  1. `evaluateJavascript` 返回的是 JSON 字符串；5–8 MB 的原始 DOM 在 JNI bridge 上
 *     会产生巨额字符串拷贝，甚至直接失败。
 *  2. 只有 page context 才拿得到**计算样式**、`getBoundingClientRect()` 与伪类状态，
 *     Kotlin 侧问不到 —— 而「这个元素到底看不看得见」就是靠这些判定的。
 *
 * ★ **隐藏过滤是整个管线的杠杆支点**：真实电商页面常有 5,000–30,000 个节点，
 *   其中 85%+ 是埋点 container、`display:none` 的 tab 面板、SVG 图标路径、
 *   移动端/PC 端重复的响应式结构。一次几何 + 可见性过滤砍掉 85%，
 *   比后面任何精妙算法都重要。
 *
 * 外部形态是纯函数 `__extract(opts)`，**可以脱离 Android 在 node/jest 里跑 fixture 测试** ——
 * 这是 :perception:dom 能快速迭代的前提（DOM 抽取逻辑单测要秒级，不要跑真机）。
 *
 * @returns {{outline, candidates, records, signals, warnings, stats}}
 */
(function (global) {
  'use strict';

  // ── 预算常量：与 Kotlin 侧 DomStats 口径一致
  var DEFAULT = {
    maxTextLen: 48,           // 元素文本截断（§12.2 MAX_TEXT_LEN）
    maxRecordsRows: 60,
    viewportExpansionPx: 800, // 视口上下各扩这么多 px
    topK: 64,
    maxNodes: 20000,          // 病态 DOM 硬上限（§5.8 病态 DOM DoS）
    maxDepth: 40,
  };

  var KICKED_TAGS = {
    SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEMPLATE: 1, SVG: 1, PATH: 1, G: 1,
    CIRCLE: 1, RECT: 1, LINE: 1, POLYGON: 1, META: 1, LINK: 1, HEAD: 1,
    DEFS: 1, SYMBOL: 1, USE: 1, CLIPPATH: 1, MASK: 1, MARKER: 1,
  };

  var ATTR_WHITELIST = [
    'aria-label', 'aria-labelledby', 'aria-describedby', 'role', 'placeholder',
    'value', 'href', 'type', 'name', 'alt', 'title', 'id', 'class',
    'aria-current', 'aria-sort', 'aria-expanded', 'aria-selected', 'aria-checked',
  ];

  var HIGH_ROLES = {
    button: 1, link: 1, textbox: 1, combobox: 1, checkbox: 1, radio: 1,
    menuitem: 1, tab: 1, option: 1, switch: 1, searchbox: 1, slider: 1, spinbutton: 1,
  };

  // ────────────────────────────────────────────────────────────── 工具

  function compactText(s) {
    if (!s) return '';
    // 压空白 + 去零宽字符（零宽字符是被用来「看起来正常但实际藏东西」的常用手段）
    var t = s.replace(/[\u200b-\u200f\u202a-\u202e\ufeff]/g, '')
      .replace(/\s+/g, ' ')
      .trim();
    return t;
  }

  function isVisible(el, win) {
    var style = win.getComputedStyle(el);
    if (!style) return false;
    if (style.display === 'none' || style.visibility === 'hidden') return false;
    if (parseFloat(style.opacity) === 0) return false;
    if (el.hasAttribute('hidden')) return false;
    if (el.getAttribute('aria-hidden') === 'true') return false;
    // 折叠 tab 面板：高度 0 且没有 overflow 滚动余量
    var r = el.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) return false;
    if (parseInt(style.zIndex, 10) < 0) return false;
    return true;
  }

  function safeSelectorPath(el) {
    // 只沿「有 id / 有稳定语义 class」的祖先走 —— 这是 stableKey 的输入之一
    var parts = [];
    var cur = el;
    var depth = 0;
    while (cur && cur.nodeType === 1 && depth < 6) {
      var seg = cur.tagName.toLowerCase();
      if (cur.id) { parts.unshift(seg + '#' + cur.id); break; }
      var cls = (cur.getAttribute('class') || '').trim().split(/\s+/)
        .filter(function (c) { return c && /^[a-zA-Z][\w-]{0,30}$/.test(c); })
        .slice(0, 2);
      if (cls.length) seg += '.' + cls.join('.');
      parts.unshift(seg);
      cur = cur.parentElement;
      depth++;
    }
    return parts.join('>');
  }

  function roleOf(el) {
    var r = el.getAttribute('role');
    if (r) return r;
    var tag = el.tagName;
    if (tag === 'A') return el.getAttribute('href') ? 'link' : '';
    if (tag === 'BUTTON') return 'button';
    if (tag === 'INPUT') {
      var t = (el.getAttribute('type') || 'text').toLowerCase();
      if (t === 'checkbox') return 'checkbox';
      if (t === 'radio') return 'radio';
      if (t === 'submit' || t === 'button') return 'button';
      return 'textbox';
    }
    if (tag === 'SELECT') return 'combobox';
    if (tag === 'TEXTAREA') return 'textbox';
    return '';
  }

  function interactiveWeight(el, role) {
    var tag = el.tagName.toLowerCase();
    if (tag === 'input' || tag === 'select' || tag === 'textarea') return 1.0;
    if (tag === 'a' && el.getAttribute('href')) return 0.9;
    if (tag === 'button') return 0.9;
    if (HIGH_ROLES[role]) return 0.85;
    if (el.hasAttribute('onclick') || (typeof el.onclick === 'function')) return 0.6;
    if (tag === 'li' || tag === 'option' || tag === 'summary' || tag === 'label') return 0.4;
    return 0.05;
  }

  // ────────────────────────────────────────────────────────────── 主函数

  /**
   * @param {Object} userOpts 覆盖 DEFAULT
   */
  function __extract(userOpts) {
    var opts = {};
    for (var k in DEFAULT) { if (DEFAULT.hasOwnProperty(k)) opts[k] = DEFAULT[k]; }
    for (var k2 in (userOpts || {})) { if (userOpts.hasOwnProperty(k2)) opts[k2] = userOpts[k2]; }

    var win = window;
    var doc = win.document;
    var result = {
      url: location.href,
      title: (doc.title || '').slice(0, 128),
      scrollY: Math.round(win.scrollY || win.pageYOffset || 0),
      maxY: Math.max(0, Math.round((doc.documentElement.scrollHeight || 0) - win.innerHeight)),
      outline: '',
      candidates: [],
      records: null,
      signals: [],
      warnings: [],
      stats: { rawNodeCount: 0, prunedNodeCount: 0, serializedChars: 0 },
    };

    var t0 = (win.performance && win.performance.now) ? win.performance.now() : Date.now();

    // ── ① 页面级信号与失效标注
    try {
      if (doc.querySelector('input[type=password]')) result.signals.push('HAS_FORM');
      if (doc.querySelector('form')) result.signals.push('HAS_FORM');
      var pag = doc.querySelector('nav[class*=pag], div[class*=pagination], ul[class*=pager]');
      if (pag) result.signals.push('PAGINATION');
      var dlg = doc.querySelector('dialog[open], [role=dialog][aria-hidden=false]');
      if (dlg) result.signals.push('MODAL_OPEN');
      var big = doc.querySelectorAll('canvas');
      var canvasArea = 0;
      for (var ci = 0; ci < big.length; ci++) {
        var cr = big[ci].getBoundingClientRect();
        canvasArea += cr.width * cr.height;
      }
      if (canvasArea > (win.innerWidth * win.innerHeight * 0.25)) result.warnings.push('CANVAS_REGION');
      if (doc.querySelectorAll('*[shadowRoot]').length > 0) result.warnings.push('SHADOW_DOM_OMITTED');
      var ifr = doc.querySelectorAll('iframe');
      for (var ii = 0; ii < ifr.length; ii++) {
        try { if (!ifr[ii].contentDocument) { result.warnings.push('IFRAME_CROSS_ORIGIN'); break; } }
        catch (e) { result.warnings.push('IFRAME_CROSS_ORIGIN'); break; }
      }
    } catch (e) { /* 信号采集失败不该让整次抽取失败 */ }

    // ── ② 遍历 + 几何/可见性预算
    var all = doc.querySelectorAll('body *');
    result.stats.rawNodeCount = all.length;
    if (all.length > opts.maxNodes) result.warnings.push('LIST_TRUNCATED');

    var vh = win.innerHeight || 800;
    var minY = -opts.viewportExpansionPx;
    var maxY = vh + opts.viewportExpansionPx;

    var candidates = [];
    var lines = [];
    var seen = 0;

    function visit(el, depth) {
      if (seen >= opts.maxNodes || depth > opts.maxDepth) return;
      var tag = el.tagName;
      if (!tag || KICKED_TAGS[tag]) return;
      seen++;
      try {
        if (!isVisible(el, win)) return;

        var rect = el.getBoundingClientRect();
        var inViewportish = opts.includeTextBelowFold
          ? true
          : (rect.bottom > minY && rect.top < maxY);
        if (!inViewportish) return;

        var role = roleOf(el);
        var text = compactText(el.textContent || '').slice(0, opts.maxTextLen);
        var w = interactiveWeight(el, role);

        // 候选：可交互元素（或有强 ARIA 语义的元素）
        if (w >= 0.4 || role) {
          var attrs = {};
          for (var ai = 0; ai < ATTR_WHITELIST.length; ai++) {
            var an = ATTR_WHITELIST[ai];
            if (el.hasAttribute(an)) {
              var av = el.getAttribute(an);
              if (av && av.length < 120) attrs[an] = av.slice(0, 120);
            }
          }
          // F3(a) 采集即脱敏：**密码/验证码类输入框的当前值不许离开 JS 边界**
          if (tag === 'INPUT') {
            var itype = (attrs['type'] || '').toLowerCase();
            if (itype === 'password' || itype === 'tel' ||
                /(password|card|cvc|cvv|idcard|security)/i.test(attrs['name'] || '')) {
              delete attrs['value'];
            }
          }
          candidates.push({
            tag: tag.toLowerCase(),
            role: role || null,
            text: text,
            attrs: attrs,
            rect: { x: Math.round(rect.x), y: Math.round(rect.y), w: Math.round(rect.width), h: Math.round(rect.height) },
            depth: depth,
            weight: w,
            selector: safeSelectorPath(el),
            offscreen: (rect.bottom < 0 || rect.top > vh),
            states: {
              visible: true,
              disabled: !!el.disabled || el.getAttribute('aria-disabled') === 'true',
              checked: el.checked === true,
              focused: doc.activeElement === el,
              required: el.hasAttribute('required'),
            },
          });
        }

        // outline：只留骨架，叶子文本不展开
        var cls = (el.getAttribute('class') || '').trim().split(/\s+/)
          .filter(function (c) { return c && /^[a-zA-Z][\w-]{0,20}$/.test(c); })
          .slice(0, 1);
        if (cls.length || el.id || role) {
          var indent = new Array(Math.min(depth, 20)).join('  ');
          lines.push(indent + tag.toLowerCase() + (el.id ? '#' + el.id : '') + (cls.length ? '.' + cls[0] : ''));
        }
      } catch (e) { /* 单个节点的异常绝不能拖垮整次抽取 */ }
    }

    var root = doc.body;
    if (root) {
      var stack = [{ el: root, d: 0 }];
      while (stack.length) {
        var cur = stack.pop();
        visit(cur.el, cur.d);
        if (seen >= opts.maxNodes) break;
        var kids = cur.el.children;
        for (var ki = 0; ki < kids.length; ki++) stack.push({ el: kids[ki], d: cur.d + 1 });
      }
    }

    result.outline = lines.join('\n');
    result.candidates = candidates;

    // ── ③ 重复子树 → RecordTable 快车道（列表页的关键手段）
    try {
      result.records = extractRecordsFromRepeats(opts);
    } catch (e) { result.records = null; }

    result.stats.prunedNodeCount = seen;
    result.stats.serializedChars = JSON.stringify(result).length;

    var t1 = (win.performance && win.performance.now) ? win.performance.now() : Date.now();
    result.elapsedMs = Math.round(t1 - t0);
    return result;
  }

  /**
   * 列表页 notoriously 有 40–60 个同构卡片。**全量展开序列化后轻松超过 40K 字符**（>10K token），
   * 直接吃穿整个上下文预算。这里把「数量认知 + 干净数据」一次性给出，
   * 而不是留一堆噪声 —— 模型在这种任务里几乎从不需要看第 37 个商品的完整 DOM。
   */
  function extractRecordsFromRepeats(opts) {
    var groups = {};
    var nodes = document.querySelectorAll('body *');
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el.children.length < 2) continue;
      var cls = (el.getAttribute('class') || '').trim().split(/\s+/)[0];
      if (!cls || cls.length > 40) continue;
      var key = el.tagName.toLowerCase() + '.' + cls;
      if (!groups[key]) groups[key] = [];
      groups[key].push(el);
      if (groups[key].length > 200) break;
    }

    var best = null;
    for (var k in groups) {
      if (!groups.hasOwnProperty(k)) continue;
      var list = groups[k];
      if (list.length < 3) continue; // ≥3 才认为是重复列表
      if (!best || list.length > best.nodes.length) best = { key: k, nodes: list };
    }
    if (!best) return null;

    // 从样本推断列：取样本内文本量最集中的直系子节点的文本作为字段
    var sample = best.nodes[0];
    var cols = [];
    var rowProto = {};
    for (var ci = 0; ci < sample.children.length && cols.length < 6; ci++) {
      var child = sample.children[ci];
      var t = compactText(child.textContent || '');
      if (!t || t.length > 80) continue;
      var cname = (child.getAttribute('class') || '').trim().split(/\s+/)[0] || child.tagName.toLowerCase();
      if (cols.indexOf(cname) >= 0) continue;
      cols.push(cname);
      rowProto[cname] = child;
    }
    if (!cols.length) return null;

    var rows = [];
    for (var ri = 0; ri < Math.min(best.nodes.length, opts.maxRecordsRows); ri++) {
      var node = best.nodes[ri];
      var row = {};
      for (var cj = 0; cj < cols.length; cj++) {
        var name = cols[cj];
        var target = null;
        var kids = node.children;
        for (var ki2 = 0; ki2 < kids.length; ki2++) {
          var kcls = (kids[ki2].getAttribute('class') || '').trim().split(/\s+/)[0] || kids[ki2].tagName.toLowerCase();
          if (kcls === name) { target = kids[ki2]; break; }
        }
        row[name] = compactText((target && target.textContent) || '').slice(0, 40);
      }
      rows.push(row);
    }

    return {
      schemaHint: '.' + best.key.split('.')[1],
      columns: cols,
      rows: rows,
      totalEstimate: best.nodes.length,
      truncated: best.nodes.length > rows.length,
    };
  }

  global.__extract = __extract;
  global.__domExtractVersion = '1.0.0';
})(typeof globalThis !== 'undefined' ? globalThis : window);
