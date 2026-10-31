package com.ake.data.persist.db

import java.io.File
import kotlin.io.path.createTempFile

/**
 * 外部 oracle 的调用封装（3→4 与 4→5 共用一份）。
 *
 * ★ 抽出来而不是在两个测试类里各写一遍的理由：oracle 是**外部进程**，
 *   调用它的那几行（找 python、找脚本、拼 JSON、读 stdout、拿退出码）
 *   每一行都可能悄悄失效；写两份就等于有两份"看起来在验、其实没验"的代码。
 */
internal object SqliteOracleRunner {

    private fun python3(): String? = listOf("python3", "python").firstOrNull { bin ->
        runCatching { ProcessBuilder(bin, "--version").start().waitFor() == 0 }.getOrDefault(false)
    }

    private fun oracleScript(): File {
        val rel = "src/test/resources/oracle/v4_migration_oracle.py"
        val candidates = listOf(File(rel), File("data/$rel"), File("../data/$rel"))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 oracle 脚本：${candidates.map { it.path }}")
    }

    private fun schemaDir(): File {
        val rel = "schemas/com.ake.data.persist.db.AkeDatabase"
        val candidates = listOf(File(rel), File("data/$rel"), File("../data/$rel"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到 Room schema 目录：$rel")
    }

    /** 跑一次 oracle，返回 (exitCode, stdout)。 */
    fun run(statements: List<String>, from: Int, to: Int): Pair<Int, String> {
        val bin = python3() ?: error("本机没有 python3 —— oracle 无法执行（不要静默跳过）")
        val stmts = createTempFile("stmts", ".json").toFile()
        stmts.writeText(jsonArray(statements))
        val proc = ProcessBuilder(
            bin,
            oracleScript().absolutePath,
            "--schema-dir", schemaDir().absolutePath,
            "--statements", stmts.absolutePath,
            "--from", from.toString(),
            "--to", to.toString(),
            "--json",
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        return code to out
    }

    private fun jsonArray(items: List<String>): String =
        items.joinToString(prefix = "[", postfix = "]") {
            "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        }
}
