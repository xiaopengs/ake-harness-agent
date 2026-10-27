"""
M10 判据的「真机证据 + 源码 oracle」两半 —— `:browser` 那批修复的回归闸门。

## 背景：为什么 M10 的判据要改

M10 原本只问「DOM 抽取有没有结果」。但 2026-10 那批 `:browser` 修复里有一条更险的事实：
**那批 bug 在真机上是静默的** —— 三段 JS 没注入、XHR 计数漂负、stableKey 两端不等价、
host 不 lowercase，全都表现为「桥超时 / 页面是空的」，和「页面真的是空的」在
现象层面**完全同形**。于是修之前 M10 一跑就挂；修之后只看「有没有 rows」又会把
「修复失效」和「今天目标站没数据」混为一谈 ⇒ 判据必须改成「验证修复真的生效」。

## 本模块提供的是判据的**静态那一半**

它不连设备，只做两件事（都用机械抽取，不许手抄）：

  1. `normalise(url)` —— 与 `com.ake.core.common.Hosts.normalise` 同口径的 Python 镜像，
     并把自己钉在**从仓库读出来的权威向量表**上（`HostsTest.HOST_VECTORS`）。
     这样既是 oracle，又不会因为 Python 侧自己算错而一直绿。
  2. `builtin_blacklist()` / `selfcheck_contract()` —— 从 `DomainPolicy.kt` 与
     `JsBootstrap.kt` 里**抽**出来的规则与自检片段：黑名单清单不是这里手抄的，
     「注入自检到底验没验 `__stableKeyOf`」也不是读注释读出来的。

★ 为什么不直接「把 `PASTEBIN.com` 喂给真机跑一遍」就算完：
  真机上要让 App 导航到一个黑名单域名，得改 App 行为或新增调试入口 —— 那是 `:browser`
  的业务逻辑改动，本次明令禁止。所以这里走的是「静态 oracle + 真机形态」两段：
  静态侧证明规则对 `PASTEBIN.com` 这类写法确实硬拒；真机侧证明**实际落库的 host**
  已经是归一化形态（大写/尾点/端口/www 一个都不该出现）。
"""

from __future__ import annotations

import os
import re
from typing import Dict, List, Optional, Set, Tuple

# ── 仓库里的权威来源（相对工程根）
HOSTS_IMPL = "core/common/src/main/java/com/ake/core/common/Hosts.kt"
HOST_VECTORS = "core/common/src/test/java/com/ake/core/common/HostsTest.kt"
DOMAIN_POLICY = "agent/src/main/java/com/ake/agent/guard/DomainPolicy.kt"
JS_BOOTSTRAP = "browser/src/main/java/com/ake/browser/JsBootstrap.kt"
DOM_EXTRACT_JS = "browser/src/main/assets/js/dom-extract.js"

#: 至少应有这么多条畸形向量 —— 少于它就是抽取器坏了，不许默默缩表
MIN_VECTORS = 29

_HIERARCHICAL = re.compile(r"^[A-Za-z][A-Za-z0-9+.\-]*://")
_PAIR = re.compile(r'"((?:[^"\\]|\\.)*)"\s+to\s+"((?:[^"\\]|\\.)*)"\s*,?')
_STRING = re.compile(r'"([^"]*)"')


def _read(project_dir: str, rel: str) -> str:
    with open(os.path.join(project_dir, rel), encoding="utf-8") as f:
        return f.read()


# ────────────────────────────────────────────────────── 归一化（Kotlin 侧的同口径镜像）

def normalise(url: str) -> str:
    """
    `com.ake.core.common.Hosts.normalise` 的逐字镜像。

    管道：trim → 只要层级协议 → 去 path/query/fragment → 去 userinfo（最后一个 `@`）
          → 去端口（IPv6 用方括号识别）→ 小写 → 去一层 `www.` → 去尾点。

    ★ IPv6 必须先看 `[`：直接按 `:` 切会把 `[::1]` 砍成 `[`。
    ★ `www.` 只剥一层：`www.www.example.com` 与 `www.example.com` 是两个 host（刻意的）。
    """
    s = (url or "").strip()
    m = _HIERARCHICAL.match(s)
    if not m:
        return ""
    authority = s[m.end():]
    for sep in ("/", "?", "#"):
        authority = authority.split(sep, 1)[0]
    without_userinfo = authority.rsplit("@", 1)[-1]
    if without_userinfo.startswith("["):
        without_port = without_userinfo.split("]", 1)[0][1:]
    else:
        without_port = without_userinfo.split(":", 1)[0]
    out = without_port.lower()
    if out.startswith("www."):
        out = out[4:]
    if out.endswith("."):
        out = out[:-1]
    return out


def shared_host_vectors(project_dir: str) -> Tuple[List[Tuple[str, str]], str]:
    """从 `HostsTest.HOST_VECTORS` 机械抽取畸形向量表。返回 (向量, 说明)。"""
    src = _read(project_dir, HOST_VECTORS)
    # ★ 锚必须是**声明**，不是第一次出现的名字（那只是某个用例里的引用）
    anchor = src.find("val HOST_VECTORS")
    if anchor < 0:
        return [], f"{HOST_VECTORS} 里找不到 `val HOST_VECTORS`"
    open_at = src.find("listOf(", anchor)
    if open_at < 0:
        return [], f"`val HOST_VECTORS` 后面找不到 `listOf(`"
    body: List[str] = []
    for line in src[open_at:].splitlines()[1:]:
        if line.strip().startswith(")"):
            break
        body.append(line)
    vectors = [(m.group(1), m.group(2)) for m in _PAIR.finditer("\n".join(body))]
    return vectors, f"抽到 {len(vectors)} 条（下限 {MIN_VECTORS}）"


def check_shared_vectors(project_dir: str) -> Tuple[bool, str]:
    """
    ★ oracle 自证：Python 镜像必须复现**仓库权威表里每一条**答案。

    谁改了 Kotlin 的管道却没同步这里（或反过来），这条立刻红 —— 于是下面那几条
    建立在 `normalise` 之上的黑名单判据才有意义（否则它只是「一个 Python 函数说它自己对」）。
    """
    vectors, detail = shared_host_vectors(project_dir)
    if len(vectors) < MIN_VECTORS:
        return False, f"{detail} ⇒ 抽取器坏了"
    bad = [f"{u!r}: 期望 {e!r}, 实得 {normalise(u)!r}" for u, e in vectors if normalise(u) != e]
    if bad:
        return False, f"{detail}；不一致 {len(bad)} 条: " + "; ".join(bad[:5])
    return True, f"{detail}，全部复现"


# ────────────────────────────────────────────────────── 黑名单（清单从源码抽，不手抄）

def builtin_blacklist(project_dir: str) -> Tuple[Set[str], str]:
    """从 `DomainPolicy.DEFAULT_BLACKLIST` 机械抽取内置黑名单。"""
    src = _read(project_dir, DOMAIN_POLICY)
    m = re.search(r"DEFAULT_BLACKLIST[^=]*=\s*setOf\(([^)]*)\)", src, re.S)
    if not m:
        return set(), "在 DomainPolicy.kt 里找不到 DEFAULT_BLACKLIST"
    return set(_STRING.findall(m.group(1))), f"抽到 {len(set(_STRING.findall(m.group(1))))} 条内置黑名单"


def blacklist_hit(host: str, blacklist: Set[str]) -> Optional[str]:
    """与 `DomainPolicy.blacklistHit` 同形：`it == host || host.endsWith(".$it")`。"""
    for it in blacklist:
        if it == host or host.endswith("." + it):
            return it
    return None


#: `PASTEBIN.com` 这一类「看着不该命中、其实必须硬拒」的写法。
#: 每一条都对应一种真实存在过的绕过：大写 / 尾点 / www + 尾点 / userinfo + 端口 / query / fragment。
HOSTILE_SPELLINGS: List[str] = [
    "https://PASTEBIN.com/raw/abcd",
    "https://PasteBin.COM/raw/abcd",
    "https://WWW.PASTEBIN.COM./raw/abcd",
    "https://pastebin.com./raw/abcd",
    "https://user@PASTEBIN.com:8443/raw/abcd",
    "https://PASTEBIN.com?next=1",
    "https://PASTEBIN.com#frag",
]

#: 反例：这几个**绝不该**被判成黑名单命中 —— 否则上面的判据就是「恒真」。
INNOCENT_SPELLINGS: List[str] = [
    "https://example.com/a",
    "https://shop.example.cn/list",
    "https://pastebincorp.example.com/a",  # endsWith(".pastebin.com") 不能误命中前缀宿主
]


def check_blacklist_hard_denies(project_dir: str) -> Tuple[bool, str]:
    """黑名单对 `PASTEBIN.com` 这类写法必须仍然硬拒（不是「需确认」也不是放行）。"""
    bl, why = builtin_blacklist(project_dir)
    if not bl:
        return False, why
    missed = [f"{u} ⇒ {normalise(u)!r}" for u in HOSTILE_SPELLINGS if blacklist_hit(normalise(u), bl) is None]
    if missed:
        return False, f"{why}；但以下写法**没**命中硬拒: " + "; ".join(missed)
    leaked = [f"{u} ⇒ {normalise(u)!r}" for u in INNOCENT_SPELLINGS if blacklist_hit(normalise(u), bl) is not None]
    if leaked:
        return False, f"{why}；但把无关域名也判成了命中（判据变恒真）: " + "; ".join(leaked)
    return True, (f"{why}；{len(HOSTILE_SPELLINGS)} 种敌意写法全部硬拒，"
                  f"{len(INNOCENT_SPELLINGS)} 个无关域名全部放行")


# ────────────────────────────────────────────────────── 注入自检（`__stableKeyOf` 16 位 hex）

def selfcheck_contract(project_dir: str) -> Tuple[bool, str]:
    """
    ★ 「真 WebView 上 `__stableKeyOf` 能算出 16 位 hex」这条判据能不能成立，
      取决于 App 自己的注入自检**到底验了什么** —— 所以它必须是读源码读出来的。

    `JsBootstrap` 的自检在**页面上下文里** `__stableKeyOf('a','','','')`，并用
    `/^[0-9a-f]{16}$/` 校验结果；不通过就把 `__stableKeyOf` 塞进缺失清单，
    `PageScriptHost` 随之打 ERROR 日志并返回 false。

    ⇒ 于是「M10 观测窗口内没有缺件日志」这件事，真的等价于
      「真机 JS 引擎算出了 16 位 hex」（而不仅仅是「函数存在」）。
      若哪天有人把这个探针删了，那条 absence 断言就会变成空转 —— 这里必须响。
    """
    try:
        boot = _read(project_dir, JS_BOOTSTRAP)
        asset = _read(project_dir, DOM_EXTRACT_JS)
    except OSError as e:
        return False, f"读不到 :browser 源码：{e}"

    missing: List[str] = []
    if "__stableKeyOf" not in boot:
        missing.append("JsBootstrap 的自检段不再探测 __stableKeyOf")
    if not re.search(r"\^\[0-9a-f\]\{16\}\$", boot):
        missing.append("JsBootstrap 的自检段不再校验 16 位 hex（正则不见了）")
    if "AKE_MISSING" not in boot:
        missing.append("JsBootstrap 不再把缺失项汇总成 AKE_MISSING（缺件清单无从产生）")
    if "global.__stableKeyOf" not in asset:
        missing.append("dom-extract.js 不再导出 __stableKeyOf")
    if missing:
        return False, "注入自检已经测不到「stableKey 在真引擎上算得出 16 位 hex」：" + "；".join(missing)
    return True, "JsBootstrap 自检确实在页面上下文里校验 __stableKeyOf ⇒ 16 位 hex"

def is_normalised_host(host: str) -> bool:
    """
    落库的 host 是否已是归一化形态。

    一个**已经落库**的值没法再传 `normalise()`（那是 URL→host，不是 host→host），
    所以这里直接按形态判：小写、无尾点、无端口、无 `www.` 前缀、无 userinfo。
    """
    if not host:
        return True
    return (host == host.lower()
            and not host.endswith(".")
            and ":" not in host
            and "@" not in host
            and not host.startswith("www."))


def run_all(project_dir: str) -> Dict[str, Tuple[bool, str]]:
    """一次性跑完本模块所有静态判据。每项都捕获异常 —— 脚本自身炸了要变成 FAIL，不是 SKIP。"""
    out: Dict[str, Tuple[bool, str]] = {}
    for name, fn in (
        ("vectors", check_shared_vectors),
        ("blacklist", check_blacklist_hard_denies),
        ("selfcheck", selfcheck_contract),
    ):
        try:
            out[name] = fn(project_dir)
        except Exception as e:  # noqa: BLE001
            out[name] = (False, f"{type(e).__name__}: {e}")
    return out
