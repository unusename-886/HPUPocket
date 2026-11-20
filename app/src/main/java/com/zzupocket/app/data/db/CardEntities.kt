package com.zzupocket.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ⚠️ 历史表：校园卡流水。
 *
 * 郑州大学版的一卡通功能已整体移除，河南理工版不读也不写这两张表。
 * 之所以保留实体定义：它们仍登记在 `AppDatabase.entities` 里，删掉会改变 Room 的
 * schema 校验哈希，老用户升级后打开数据库会直接崩 —— 要删必须同时升版本并写迁移。
 * 表里没有新数据写入，只是历史残留；等将来做一次正式迁移时一并清掉。
 *
 * 主键用**流水号**（`journo`）—— 天然唯一、天然幂等，
 * 所以"重复拉同一段时间"不会重复计，也不需要去重逻辑。
 */
@Entity(
    tableName = "card_transactions",
    indices = [Index("ts")],
)
data class CardTransactionEntity(
    @PrimaryKey val journo: String,
    val summary: String,
    val merchantName: String,
    /** 服务端原始时间文本 "2026-09-19 17:45:17"（解析失败时留档用） */
    val dateText: String,
    /** 本地时区下的时间戳，用于排序与聚合；0 = 解析失败 */
    val ts: Long,
    /** 单位：元。消费为负、充值为正 —— 服务端就是这么给的 */
    val amount: Double,
    val isRefund: Boolean,
    val tradeType: String,
    val fetchedAt: Long,
)
