#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
模型分发 manifest 的**运维侧生成器 / 验签器**。

=============================================================================
本文件的每一个字节规则都来自客户端代码，**不是**设计出来的。出处：

  data/src/main/java/com/ake/data/persist/modeldist/
      ModelManifest.kt          领域模型 + ManifestSignature + 错误码
      ManifestCanonicalForm.kt  ★ 签名覆盖的规范化字节（本文件逐字复刻）
      ManifestParser.kt         字段校验（https / 64hex / 去重 / 严格 JSON）
      ManifestTrust.kt          验签：只认 alg = "RSA-SHA256"
      StrictJson.kt             quote() 的转义集合 + 严格解析
      ManifestTransport.kt      拉取：不跟随重定向、no-cache、1 MiB 上限
=============================================================================

★ 为什么必须复刻而不是「按 JSON 常识写」
  manifest 的签名**不覆盖传输字节**，覆盖的是双方各自重新序列化出来的
  `ManifestCanonicalForm.render()` 结果。所以哪怕键顺序、缩进、转义差一个字符，
  客户端就会算出另一份 canonical bytes ⇒ 签名恒不成立 ⇒ **全网设备同时拉不到模型**。
  这种故障在线上的表现是「所有用户同一时刻没有模型」，且日志里只有一个
  `MANIFEST_SIGNATURE_INVALID`，看不出是空格问题还是密钥问题。

  ⇒ 本文件 `--self-test` 用一条从 Kotlin 侧 `ManifestCanonicalForm.render()` 实拍
    得到的 golden 字节做断言（见 GOLDEN_* 常量）。改本文件前先看那条测试。

用法
----
  # 1) 生成密钥对（私钥由运维保管，**绝不进仓库**；公钥是公开信息）
  ./scripts/model_manifest.py keygen --out-dir .local --key-id model-ca-2026

  # 2) 由模型文件生成并签名 manifest
  ./scripts/model_manifest.py manifest \
      --file /path/to/gemma4-int4.litertlm \
      --model-id gemma-4-e2b-it --variant int4 \
      --revision 5 --version 2026.10.5 \
      --url https://cdn.ake.cn/models/gemma-4-e2b-it/int4/r5.litertlm \
      --min-app-version-code 1 --context-window 8192 --min-ram-gb 6 \
      --capabilities TEXT,TOOLS \
      --manifest-version 7 \
      --signing-key .local/manifest-signing-private.pem \
      --key-id model-ca-2026 \
      --out dist/model-manifest.json

  # 3) 上线前自检（验签 + 负向用例）
  ./scripts/model_manifest.py verify \
      --manifest dist/model-manifest.json \
      --public-key .local/manifest-signing-public.pem
  ./scripts/model_manifest.py self-test

依赖：python3 + openssl（都在 CI runner 与 macOS 上默认存在，零 pip 依赖）。
      刻意不引 cryptography / PyJWT：运维脚本的依赖越少，越不会在某台机器上装不上。
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import subprocess
import sys
import tempfile

# ─────────────────────────────────────────────────────────────────────────────
# 常量：与客户端一一对应，改这里等于改线上信任链
# ─────────────────────────────────────────────────────────────────────────────

SCHEMA_VERSION = 1          # ManifestSchema.CURRENT；客户端只接受这一个
ALG = "RSA-SHA256"          # ManifestTrust.ALG_RSA_SHA256；客户端只接受这一个
OPENSSL_DIGEST = "sha256"   # SHA256withRSA == PKCS#1 v1.5 over SHA-256
RSA_BITS = 2048

# ─────────────────────────────────────────────────────────────────────────────
# ★★ golden 字节 —— 由 Kotlin 侧 ManifestCanonicalForm.render() 实拍得到
#
#  夹具（与 :llm 的 ManifestFailClosedTest 里 FIXTURE 完全一致）：
#    schema_version=1, manifest_version=7, generated_at=1700000000000
#    models=[{gemma-4-e2b-it / int4 / revision=5 / version="2026.10.5"
#             url=https://cdn.ake.cn/models/gemma-4-e2b-it/int4/r5.litertlm
#             size_bytes=2782923040, sha256=<64 个 'a'>
#             min_app_version_code=1, max_app_version_code=null
#             context_window=8192, min_ram_gb=6
#             capabilities=["TEXT","TOOLS"], state=ACTIVE}]
#
#  两边都断言这一串字节：Python 这边 self-test 比对，Kotlin 那边
#  `ManifestCanonicalForm.render(manifest)` 比对。任何一侧漂移都会红。
# ─────────────────────────────────────────────────────────────────────────────
GOLDEN_CANONICAL = (
    '{"schema_version":1,"manifest_version":7,"generated_at":1700000000000,'
    '"models":[{"model_id":"gemma-4-e2b-it","variant":"int4","revision":5,'
    '"version":"2026.10.5",'
    '"url":"https://cdn.ake.cn/models/gemma-4-e2b-it/int4/r5.litertlm",'
    '"size_bytes":2782923040,'
    '"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",'
    '"min_app_version_code":1,"max_app_version_code":null,'
    '"context_window":8192,"min_ram_gb":6,'
    '"capabilities":["TEXT","TOOLS"],"state":"ACTIVE"}]}'
)


# ═════════════════════════════════════════════════════════════════════════════
# 规范化 —— StrictJson.quote + ManifestCanonicalForm.render 的逐行对译
# ═════════════════════════════════════════════════════════════════════════════

def quote(raw: str) -> str:
    """
    复刻 `StrictJson.quote`（StrictJson.kt:82）。

    ★ 只转义 JSON 要求的 7 个字符 + 其余控制字符；**非 ASCII 直出 UTF-8**。
    ★ 特别注意 **'/' 不转义**：manifest 里全是 URL，`\\/` 与 `/` 会被算成
      不同的 canonical 字节（虽然 StrictJson 解析时能接受 `\\/`）。
    """
    out = ['"']
    for ch in raw:
        code = ord(ch)
        if ch == '"':
            out.append('\\"')
        elif ch == '\\':
            out.append('\\\\')
        elif ch == '\n':
            out.append('\\n')
        elif ch == '\r':
            out.append('\\r')
        elif ch == '\t':
            out.append('\\t')
        elif code == 0x08:      # Kotlin: c == '\b'
            out.append('\\b')
        elif code == 0x0C:      # Kotlin: c == '\f'
            out.append('\\f')
        elif code < 0x20:       # Kotlin: c < ' '
            out.append('\\u%04x' % code)
        else:
            out.append(ch)
    out.append('"')
    return ''.join(out)


def _utf16_key(s: str) -> bytes:
    """
    `compareBy({ it.modelId }, { it.variant })` 的对译。

    Kotlin 的 String.compareTo 按 **UTF-16 code unit** 比较，Python 默认按 **code point**。
    两者只在 U+10000 以上的字符（emoji 等）上有分歧：
      UTF-16 里代理对 0xD800-0xDFFF 排在 U+E000-U+FFFF **之前**，
      Python 里 U+10000+ 排在 U+FFFF **之后**。
    model_id / variant 今天是纯 ASCII，但一旦有人塞个 emoji 进去，
    排序不同 ⇒ 签名不通 ⇒ 全网拉不到模型。用 UTF-16-BE 字节做键即可逐位对齐。
    """
    return s.encode('utf-16-be')


def render_canonical(entry_list: list[dict], schema_version: int,
                     manifest_version: int, generated_at: int) -> bytes:
    """
    复刻 `ManifestCanonicalForm.render`（ManifestCanonicalForm.kt:28）。

    规则（改动任何一条 = 线上全量验签失败，必须同时改服务端与客户端）：
      1. 不含 `signature` 字段本身；
      2. 键顺序固定，**不**按字典序；
      3. `models` 按 (model_id, variant) 升序；
      4. `max_app_version_code` 缺失与显式 null 等价（都渲染成 `null`）；
      5. 输出编码 UTF-8。
    """
    sb = ['{']
    sb.append(quote("schema_version") + ':' + str(schema_version) + ',')
    sb.append(quote("manifest_version") + ':' + str(manifest_version) + ',')
    sb.append(quote("generated_at") + ':' + str(generated_at) + ',')
    sb.append(quote("models") + ':[')

    ordered = sorted(entry_list,
                     key=lambda e: (_utf16_key(e["model_id"]),
                                    _utf16_key(e["variant"])))
    for i, e in enumerate(ordered):
        if i > 0:
            sb.append(',')
        sb.append(_render_entry(e))
    sb.append(']')
    sb.append('}')
    return ''.join(sb).encode('utf-8')


def _render_entry(e: dict) -> str:
    sb = ['{']
    sb.append(quote("model_id") + ':' + quote(e["model_id"]) + ',')
    sb.append(quote("variant") + ':' + quote(e["variant"]) + ',')
    sb.append(quote("revision") + ':' + str(e["revision"]) + ',')
    sb.append(quote("version") + ':' + quote(e["version"]) + ',')
    sb.append(quote("url") + ':' + quote(e["url"]) + ',')
    sb.append(quote("size_bytes") + ':' + str(e["size_bytes"]) + ',')
    # 客户端会把 sha256 lower() 后再校验；这里同步小写，避免大小写造成 canonical 分歧
    sb.append(quote("sha256") + ':' + quote(e["sha256"].lower()) + ',')
    sb.append(quote("min_app_version_code") + ':' + str(e["min_app_version_code"]) + ',')
    mx = e.get("max_app_version_code")
    sb.append(quote("max_app_version_code") + ':'
              + (str(mx) if mx is not None else 'null') + ',')
    sb.append(quote("context_window") + ':' + str(e["context_window"]) + ',')
    sb.append(quote("min_ram_gb") + ':' + str(e["min_ram_gb"]) + ',')
    caps = e.get("capabilities") or []
    sb.append(quote("capabilities") + ':['
              + ','.join(quote(c) for c in caps) + ']' + ',')
    sb.append(quote("state") + ':' + quote(str(e["state"]).upper()))
    sb.append('}')
    return ''.join(sb)


# ═════════════════════════════════════════════════════════════════════════════
# 客户端侧的字段校验（ManifestParser 的对译）—— 上线前就该拦住，别等用户反馈
# ═════════════════════════════════════════════════════════════════════════════

def validate_entry(e: dict) -> list[str]:
    """返回问题列表；空列表 = 客户端会接受。每条都对应客户端一个错误码。"""
    problems: list[str] = []
    if not str(e.get("model_id", "")).strip():
        problems.append("model_id 空白 ⇒ MANIFEST_FIELD_INVALID")
    if not str(e.get("variant", "")).strip():
        problems.append("variant 空白 ⇒ MANIFEST_FIELD_INVALID")
    if int(e["revision"]) < 0:
        problems.append("revision < 0 ⇒ MANIFEST_FIELD_INVALID")

    url = str(e.get("url", ""))
    # ★ 客户端硬要求 https：http 会被运营商注入改写
    if not url.startswith("https://"):
        problems.append("url 必须 https:// ⇒ MANIFEST_FIELD_INVALID（客户端硬拒）")

    if int(e["size_bytes"]) <= 0:
        problems.append("size_bytes <= 0 ⇒ MANIFEST_FIELD_INVALID")

    sha = e["sha256"].lower()
    if len(sha) != 64 or any(c not in "0123456789abcdef" for c in sha):
        problems.append("sha256 必须是 64 位小写 hex ⇒ MANIFEST_FIELD_INVALID")

    if int(e["min_app_version_code"]) < 1:
        problems.append("min_app_version_code < 1 ⇒ MANIFEST_FIELD_INVALID")
    mx = e.get("max_app_version_code")
    if mx is not None and int(mx) < int(e["min_app_version_code"]):
        problems.append("max < min ⇒ MANIFEST_FIELD_INVALID")

    if int(e["context_window"]) <= 0:
        problems.append("context_window <= 0 ⇒ MANIFEST_FIELD_INVALID")
    if int(e.get("min_ram_gb", 0)) < 0:
        problems.append("min_ram_gb < 0 ⇒ MANIFEST_FIELD_INVALID")

    if str(e["state"]).upper() not in ("ACTIVE", "DEPRECATED", "REVOKED"):
        problems.append("state 必须是 ACTIVE/DEPRECATED/REVOKED ⇒ MANIFEST_FIELD_INVALID")
    return problems


# ═════════════════════════════════════════════════════════════════════════════
# 文件摘要 / 签名
# ═════════════════════════════════════════════════════════════════════════════

def sha256_of_file(path: str) -> str:
    """流式摘要 —— 2.6 GB 不能读进内存（与客户端 `ModelDownloader.sha256Of` 同思路）。"""
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def _openssl(args: list[str]) -> bytes:
    return subprocess.run(["openssl"] + args, check=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def _openssl_ok(args: list[str]) -> bool:
    r = subprocess.run(["openssl"] + args, stdout=subprocess.PIPE,
                       stderr=subprocess.PIPE)
    return r.returncode == 0


def sign_bytes(canonical: bytes, private_key_pem: str) -> str:
    """
    RSA-SHA256（PKCS#1 v1.5 over SHA-256）签名，返回**标准 base64**（带 padding，不换行）。

    为什么调 openssl 而不是引 cryptography：
      运维脚本要在 CI runner、运维笔记本、跳板机上都能跑，零 pip 依赖比优雅更重要。
      `openssl dgst -sha256 -sign` 产出的正是 `SHA256withRSA` 的字节，
      与客户端 `Signature.getInstance("SHA256withRSA")` 完全对齐。
    """
    with tempfile.TemporaryDirectory() as td:
        cpath = os.path.join(td, "canonical.bin")
        spath = os.path.join(td, "sig.bin")
        with open(cpath, 'wb') as f:
            f.write(canonical)
        _openssl(["dgst", "-" + OPENSSL_DIGEST, "-sign", private_key_pem,
                  "-out", spath, cpath])
        with open(spath, 'rb') as f:
            raw = f.read()
    # Java 侧用 Base64.getEncoder()（标准 base64，带 = padding，不换行）解码
    return base64.b64encode(raw).decode('ascii')


def verify_bytes(canonical: bytes, sig_b64: str, public_key_pem: str) -> bool:
    with tempfile.TemporaryDirectory() as td:
        cpath = os.path.join(td, "canonical.bin")
        spath = os.path.join(td, "sig.bin")
        with open(cpath, 'wb') as f:
            f.write(canonical)
        with open(spath, 'wb') as f:
            f.write(base64.b64decode(sig_b64))
        return _openssl_ok(["dgst", "-" + OPENSSL_DIGEST, "-verify", public_key_pem,
                            "-signature", spath, cpath])


def public_key_spki_base64(private_key_pem: str) -> str:
    """
    X.509 SubjectPublicKeyInfo 的 DER（标准 base64）—— **这就是要钉进 APK 的那一串**。

    客户端 `ManifestTrust.rsaSha256Verifier` 只接受这种格式：
        openssl rsa -pubout -outform DER | base64
    给它裸 modulus（PKCS#1）会让 `X509EncodedKeySpec` 静默失败，
    真机表现为「所有 manifest 都验签失败」，排查成本极高。
    """
    der = _openssl(["rsa", "-in", private_key_pem, "-pubout", "-outform", "DER"])
    return base64.b64encode(der).decode('ascii')


# ═════════════════════════════════════════════════════════════════════════════
# 文档渲染
# ═════════════════════════════════════════════════════════════════════════════

def build_document(entries: list[dict], manifest_version: int, generated_at: int,
                   signing_key: str | None, key_id: str | None) -> tuple[str, bytes]:
    """
    产出要发到 CDN 的 JSON 文本，并返回它对应的 canonical bytes。

    ★ 顺序：先按字段渲染 canonical（**不含** signature）⇒ 签名 ⇒ 再把 signature 塞进文档。
      反过来（先拼文档再签）会把 signature 自己签进去，客户端永远验不过。
    """
    canonical = render_canonical(entries, SCHEMA_VERSION, manifest_version, generated_at)

    if not signing_key:
        raise SystemExit("未给 --signing-key：客户端对没有 signature 块的 manifest "
                         "一律返回 MANIFEST_SIGNATURE_MISSING ⇒ 不会下载。"
                         "确实要产出未签名样本请显式传 --allow-unsigned。")

    signature = {
        "alg": ALG,
        "key_id": key_id or "",
        "value": sign_bytes(canonical, signing_key),
    }

    # 传输层字节：json.dumps 的输出是严格 JSON（无尾逗号、无注释、无 NaN），
    # 能过客户端的 StrictJson。ensure_ascii=True 让输出纯 ASCII，避开 CDN 转码风险。
    doc = {
        "schema_version": SCHEMA_VERSION,
        "manifest_version": manifest_version,
        "generated_at": generated_at,
        "models": [_wire_entry(e) for e in entries],
        "signature": signature,
    }
    return json.dumps(doc, ensure_ascii=True, indent=2, sort_keys=False), canonical


def _wire_entry(e: dict) -> dict:
    """传输形态：字段顺序与客户端 fixtures 一致，未知字段会被客户端丢弃（不进签名）。"""
    return {
        "model_id": e["model_id"],
        "variant": e["variant"],
        "revision": int(e["revision"]),
        "version": e["version"],
        "url": e["url"],
        "size_bytes": int(e["size_bytes"]),
        "sha256": e["sha256"].lower(),
        "min_app_version_code": int(e["min_app_version_code"]),
        "max_app_version_code": e.get("max_app_version_code"),
        "context_window": int(e["context_window"]),
        "min_ram_gb": int(e.get("min_ram_gb", 0)),
        "capabilities": list(e.get("capabilities") or []),
        "state": str(e["state"]).upper(),
    }


# ═════════════════════════════════════════════════════════════════════════════
# 子命令
# ═════════════════════════════════════════════════════════════════════════════

def cmd_keygen(a: argparse.Namespace) -> int:
    os.makedirs(a.out_dir, exist_ok=True)
    priv = os.path.join(a.out_dir, "manifest-signing-private.pem")
    pub = os.path.join(a.out_dir, "manifest-signing-public.pem")
    if os.path.exists(priv) and not a.force:
        print(f"已存在：{priv}（--force 覆盖）")
    else:
        _openssl(["genpkey", "-algorithm", "RSA",
                  "-pkeyopt", f"rsa_keygen_bits:{RSA_BITS}", "-out", priv])
        os.chmod(priv, 0o600)
        print(f"私钥（绝不进仓库）：{priv}")

    _openssl(["rsa", "-in", priv, "-pubout", "-out", pub])
    spki = public_key_spki_base64(priv)

    print("\n" + "=" * 78)
    print("  公钥是公开信息 —— 把下面这一串钉进 ModelManifestConfig.pinnedKeys：")
    print("=" * 78)
    if a.key_id:
        print(f'  "{a.key_id}" to "{spki}",')
    else:
        print(f'  "model-ca-2026" to "{spki}",')
    print("=" * 78)
    print("\n私钥保管：放密钥管理服务（Vault / KMS / Secrets Manager）。")
    print("本机只留 .local/（已 gitignore），且 .gitignore 对 *.pem /*.key 有独立兜底。")
    return 0


def cmd_manifest(a: argparse.Namespace) -> int:
    entries: list[dict] = []

    if a.entries:
        # 批量：--entries spec.json，形如 {"entries":[{...含 file 路径...}]}
        with open(a.entries, 'r', encoding='utf-8') as f:
            spec = json.load(f)
        for raw in spec["entries"]:
            entries.append(_entry_from_spec(raw, a))
    else:
        if not a.file:
            raise SystemExit("需要 --file（或 --entries spec.json）")
        entries.append(_entry_from_spec({
            "file": a.file,
            "model_id": a.model_id,
            "variant": a.variant,
            "revision": a.revision,
            "version": a.version,
            "url": a.url,
            "min_app_version_code": a.min_app_version_code,
            "max_app_version_code": a.max_app_version_code,
            "context_window": a.context_window,
            "min_ram_gb": a.min_ram_gb,
            "capabilities": a.capabilities,
            "state": a.state,
        }, a))

    # ── 客户端会拒绝的东西，在这里就拦下（别等 2.6 GB 下完才发现）
    bad = False
    seen: set[tuple[str, str]] = set()
    for e in entries:
        problems = validate_entry(e)
        key = (e["model_id"], e["variant"])
        if key in seen:
            problems.append("(model_id, variant) 重复 ⇒ MANIFEST_DUPLICATE_VARIANT")
        seen.add(key)
        if problems:
            bad = True
            print(f"[FAIL] {key}:")
            for p in problems:
                print(f"         - {p}")
    if bad:
        raise SystemExit("manifest 含客户端会拒绝的字段 ⇒ 已中止（未产出文件）")

    doc, canonical = build_document(
        entries, a.manifest_version, a.generated_at,
        None if a.allow_unsigned else a.signing_key, a.key_id,
    )

    if a.out:
        os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
        with open(a.out, 'w', encoding='utf-8') as f:
            f.write(doc + "\n")
        print(f"已写入 {a.out}（{len(doc)} 字节）")
    else:
        print(doc)

    if a.print_canonical:
        sys.stderr.write("\n[cannonical bytes] " + canonical.decode('utf-8') + "\n")
    if a.allow_unsigned:
        sys.stderr.write(
            "\n[警告] 产出的是**未签名** manifest：客户端会返回 "
            "MANIFEST_SIGNATURE_MISSING 并拒绝下载。仅用于本地联调。\n")
    return 0


def _entry_from_spec(raw: dict, a: argparse.Namespace) -> dict:
    path = raw.get("file")
    if not path:
        raise SystemExit(f"条目缺少 file：{raw}")
    if not os.path.isfile(path):
        raise SystemExit(f"模型文件不存在：{path}")

    size = os.path.getsize(path)
    print(f"  计算 SHA-256：{path}（{size} 字节）…", flush=True)
    sha = sha256_of_file(path)

    max_app = raw.get("max_app_version_code", getattr(a, "max_app_version_code", None))
    caps = raw.get("capabilities", a.capabilities)
    if isinstance(caps, str):
        caps = [c for c in caps.split(",") if c]

    return {
        "model_id": raw.get("model_id", a.model_id),
        "variant": raw.get("variant", a.variant),
        "revision": int(raw.get("revision", a.revision)),
        "version": raw.get("version", a.version),
        "url": raw.get("url", a.url),
        "size_bytes": size,
        "sha256": sha,
        "min_app_version_code": int(raw.get("min_app_version_code",
                                            a.min_app_version_code)),
        "max_app_version_code": (int(max_app) if max_app is not None else None),
        "context_window": int(raw.get("context_window", a.context_window)),
        "min_ram_gb": int(raw.get("min_ram_gb", a.min_ram_gb)),
        "capabilities": caps or [],
        "state": raw.get("state", a.state),
    }


def cmd_verify(a: argparse.Namespace) -> int:
    with open(a.manifest, 'r', encoding='utf-8') as f:
        doc = json.load(f)
    sig = doc.get("signature")
    if not sig:
        print("[FAIL] 没有 signature 块 ⇒ 客户端返回 MANIFEST_SIGNATURE_MISSING")
        return 1
    if sig.get("alg") != ALG:
        print(f"[FAIL] alg={sig.get('alg')} ⇒ 客户端返回 MANIFEST_SIGNATURE_ALG_UNSUPPORTED")
        return 1

    entries = doc["models"]
    canonical = render_canonical(entries, doc["schema_version"],
                                 doc["manifest_version"], doc.get("generated_at", 0))
    ok = verify_bytes(canonical, sig["value"], a.public_key)
    if not ok:
        print("[FAIL] MANIFEST_SIGNATURE_INVALID —— 客户端会拒绝下载")
        return 1

    print(f"[OK] 签名有效（alg={sig['alg']}, key_id={sig.get('key_id')}）")
    for e in entries:
        print(f"     {e['model_id']}/{e['variant']} r{e['revision']} "
              f"{e['size_bytes']}B {e['sha256'][:16]}… {e['state']}")
    return 0


def cmd_self_test(a: argparse.Namespace) -> int:
    """
    fail-closed 的离线证明（不需要真 CDN、不需要真模型文件）。

    跑的是**客户端的判定规则**在本文件里的对译，负向用例才是重点：
    一条「坏签名会被放行」的实现，会让整条链路的保护形同虚设。
    """
    fixture = {
        "model_id": "gemma-4-e2b-it",
        "variant": "int4",
        "revision": 5,
        "version": "2026.10.5",
        "url": "https://cdn.ake.cn/models/gemma-4-e2b-it/int4/r5.litertlm",
        "size_bytes": 2782923040,
        "sha256": "a" * 64,
        "min_app_version_code": 1,
        "max_app_version_code": None,
        "context_window": 8192,
        "min_ram_gb": 6,
        "capabilities": ["TEXT", "TOOLS"],
        "state": "ACTIVE",
    }

    failures: list[str] = []

    def check(name: str, cond: bool) -> None:
        print(("  [OK]   " if cond else "  [FAIL] ") + name)
        if not cond:
            failures.append(name)

    print("① 规范化字节必须与 Kotlin 侧 ManifestCanonicalForm.render 实拍值一致")
    got = render_canonical([fixture], 1, 7, 1700000000000).decode('utf-8')
    check("canonical bytes == GOLDEN", got == GOLDEN_CANONICAL)
    if got != GOLDEN_CANONICAL:
        print("        expected: " + GOLDEN_CANONICAL)
        print("        actual  : " + got)

    print("\n② 规范化规则的性质")
    shuffled = [dict(fixture), {**fixture, "model_id": "aaa-model", "variant": "int8"}]
    a1 = render_canonical(shuffled, 1, 7, 1)
    a2 = render_canonical(list(reversed(shuffled)), 1, 7, 1)
    check("条目顺序无关（按 model_id,variant 排序）", a1 == a2)
    check("缺失 max 与显式 null 等价",
          render_canonical([{**fixture, "max_app_version_code": None}], 1, 7, 1)
          == render_canonical([{k: v for k, v in fixture.items()
                                if k != "max_app_version_code"}], 1, 7, 1))
    check("URL 里的 / 不转义", "/" in GOLDEN_CANONICAL.split('"url":"')[1].split('"')[0])

    print("\n③ 字段校验（客户端会拒的东西必须先在这拦住）")
    check("http:// 的 url 被拦下",
          any("https" in p for p in validate_entry({**fixture, "url": "http://x/y"})))
    check("非 hex 的 sha256 被拦下",
          any("sha256" in p for p in validate_entry({**fixture, "sha256": "zz"})))
    check("size_bytes<=0 被拦下",
          any("size_bytes" in p for p in validate_entry({**fixture, "size_bytes": 0})))

    print("\n④ 签名 → 验签 → 篡改必失败（fail-closed 的负向用例）")
    with tempfile.TemporaryDirectory() as td:
        priv = os.path.join(td, "k.pem")
        pub = os.path.join(td, "k.pub.pem")
        _openssl(["genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048",
                  "-out", priv])
        _openssl(["rsa", "-in", priv, "-pubout", "-out", pub])

        canonical = render_canonical([fixture], 1, 7, 1700000000000)
        sig = sign_bytes(canonical, priv)
        check("有效签名通过", verify_bytes(canonical, sig, pub))

        # 篡改内容（revision 5 → 9）：canonical 变了，旧签名必须失效
        tampered = render_canonical([{**fixture, "revision": 9}], 1, 7, 1700000000000)
        check("内容被篡改 ⇒ 拒绝", not verify_bytes(tampered, sig, pub))

        # 换一把钥匙签：key_id 未登记 ⇒ 客户端 SIGNATURE_KEY_UNKNOWN；
        # 即便钥匙被登记了，签名也必须不成立
        priv2 = os.path.join(td, "k2.pem")
        pub2 = os.path.join(td, "k2.pub.pem")
        _openssl(["genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048",
                  "-out", priv2])
        _openssl(["rsa", "-in", priv2, "-pubout", "-out", pub2])
        check("换私钥签 ⇒ 拒绝", not verify_bytes(canonical, sign_bytes(canonical, priv2), pub))

        # 签名值坏一个字节
        raw = bytearray(base64.b64decode(sig))
        raw[0] ^= 0xFF
        check("签名值坏一个字节 ⇒ 拒绝",
              not verify_bytes(canonical, base64.b64encode(bytes(raw)).decode(), pub))

        print("\n   公钥钉扎形态（SPKI DER base64，钉进 APK 的那一串）:")
        print("   " + public_key_spki_base64(priv))

    print("\n" + "=" * 78)
    if failures:
        print(f"SELF-TEST FAILED：{len(failures)} 项 —— " + "、".join(failures))
        return 1
    print("SELF-TEST PASSED —— 规范化、字段校验、验签与 fail-closed 全部成立")
    return 0


# ═════════════════════════════════════════════════════════════════════════════

def main() -> int:
    p = argparse.ArgumentParser(
        description="模型分发 manifest 的运维侧生成器 / 验签器（格式以客户端代码为准）")
    sub = p.add_subparsers(dest="cmd", required=True)

    g = sub.add_parser("keygen", help="生成 manifest 签名密钥对")
    g.add_argument("--out-dir", default=".local")
    g.add_argument("--key-id", default="model-ca-2026")
    g.add_argument("--force", action="store_true")
    g.set_defaults(fn=cmd_keygen)

    m = sub.add_parser("manifest", help="由模型文件生成并签名 manifest")
    m.add_argument("--file", help="单个模型文件路径")
    m.add_argument("--entries", help="批量 spec.json（含多个条目的 file 与元数据）")
    m.add_argument("--model-id", default="gemma-4-e2b-it")
    m.add_argument("--variant", default="int4")
    m.add_argument("--revision", type=int, default=1)
    m.add_argument("--version", default="2026.10.1")
    m.add_argument("--url", default="https://cdn.ake.cn/models/gemma-4-e2b-it/int4/r1.litertlm")
    m.add_argument("--min-app-version-code", type=int, default=1)
    m.add_argument("--max-app-version-code", type=int, default=None)
    m.add_argument("--context-window", type=int, default=8192)
    m.add_argument("--min-ram-gb", type=int, default=6)
    m.add_argument("--capabilities", default="TEXT,TOOLS")
    m.add_argument("--state", default="ACTIVE",
                   choices=["ACTIVE", "DEPRECATED", "REVOKED"])
    m.add_argument("--manifest-version", type=int, required=True,
                   help="★ 永远单调递增（回滚也要 +1），否则 CDN 缓存可能永远吐旧副本")
    m.add_argument("--generated-at", type=int, default=None,
                   help="毫秒时间戳；缺省取当前时间")
    m.add_argument("--signing-key", help="私钥 PEM 路径")
    m.add_argument("--key-id", default="model-ca-2026")
    m.add_argument("--out", help="输出路径；不给则打印到 stdout")
    m.add_argument("--print-canonical", action="store_true", help="打印规范化字节（排障用）")
    m.add_argument("--allow-unsigned", action="store_true",
                   help="产出未签名样本（客户端会拒绝，仅供本地联调）")
    m.set_defaults(fn=cmd_manifest)

    v = sub.add_parser("verify", help="验签一份已生成的 manifest")
    v.add_argument("--manifest", required=True)
    v.add_argument("--public-key", required=True)
    v.set_defaults(fn=cmd_verify)

    s = sub.add_parser("self-test", help="离线自检（含 fail-closed 负向用例）")
    s.set_defaults(fn=cmd_self_test)

    a = p.parse_args()
    if a.cmd == "manifest" and a.generated_at is None:
        import time
        a.generated_at = int(time.time() * 1000)
    return a.fn(a)


if __name__ == "__main__":
    sys.exit(main())
