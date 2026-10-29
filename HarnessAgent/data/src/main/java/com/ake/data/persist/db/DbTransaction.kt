package com.ake.data.persist.db

import androidx.room.withTransaction

/**
 * ★★ 事务的**可替换接缝**（docs/09 §6.2 的实现约束）。
 *
 * ## 为什么需要它，而不是直接用 `db.withTransaction { … }`
 *
 * `androidx.room.RoomDatabase.withTransaction` 依赖 `internalTransactionExecutor`
 * —— 那是**真开库**时才被赋值的 `lateinit`。JVM 单测里构造不出一个"开着的"
 * `AkeDatabase`（`SupportSQLiteOpenHelper` 在 unit test 下是 Android stub）
 * ⇒ 谁在业务代码里直接调 `withTransaction`，谁的**整条逻辑就不可测**。
 *
 * 出境审计闭环（先落 PENDING 再发字节、幂等键、fail-closed）恰恰是**最该被测**
 * 的那部分逻辑 —— 它一旦错了，表现是「数据出去了但没记账」，且**不可见**。
 * 让它只能靠真机验证（`docs/07`：冷启 4.7 分钟）是不可接受的。
 *
 * ⇒ 把「在事务里跑一段」抽成一个接口：生产侧指向 `RoomDatabase.withTransaction`，
 *   测试侧指向一个直接执行的替身。**行为完全不变**，只是多了一层可替换性。
 *
 * ## ⚠ 它**不**削弱什么
 *
 * 事务本身（原子性 / 隔离级别）仍由 Room + SQLite 提供；本接口只负责"把这段代码
 * 放进事务里"这个动作。替身不模拟事务 ⇒ 「UNIQUE 真能挡住并发第二次插入」
 * 仍然只能由 `SqliteOracleV4Test`（外部 oracle，真 SQLite）与真机证明。
 */
// ⚠ 刻意**不是** `fun interface`：Kotlin 不允许 SAM 接口的唯一抽象方法带类型参数
//   （`Functional interface cannot have an abstract method with type parameters`），
//   而 `<T> run(...)` 必须带（否则事务里的返回值类型会被抹成 Any?）。
public interface DbTransaction {

    /** 在事务内执行 [block] 并返回其结果。 */
    public suspend fun <T> run(block: suspend () -> T): T

    public companion object {

        /** 生产实现：**就是** `RoomDatabase.withTransaction`，没有额外的东西。 */
        public fun of(db: AkeDatabase): DbTransaction = object : DbTransaction {
            override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction(block)
        }
    }
}
