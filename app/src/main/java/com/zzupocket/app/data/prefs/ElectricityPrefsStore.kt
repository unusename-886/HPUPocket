package com.zzupocket.app.data.prefs

import android.content.Context
import com.zzupocket.app.data.electricity.SdcxElectricityClient
import com.zzupocket.app.data.electricity.SdcxParser

/**
 * 宿舍用电的**选择记忆**与**上次读数**。
 *
 * 存两样东西：
 *  1. 上次选的校区/楼栋/楼层/房间 —— 下次进页面直接恢复，不用重选一遍（用户要求"选一次后记住"）；
 *  2. 上次查到的照明/空调电量 —— 首页课表下方的电量卡只读这里，秒开不联网。
 *
 * 首页卡上的刷新按钮会用 (1) 重放一次查询，所以选择必须存**可回放的编号**
 * （楼栋基础名 + 楼层号 + 房间号），不能只存展示文案。
 * 不含任何凭据（电费站免登录）。
 */
class ElectricityPrefsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Cached(
        val campusKey: String = SdcxElectricityClient.KEY_SOUTH,
        val campusLabel: String = "南校区",
        val base: String = "",
        val floorId: String = "",
        val floorLabel: String = "",
        val roomId: String = "",
        val roomLabel: String = "",
        val lightKwh: Double? = null,
        val acKwh: Double? = null,
        val timeMillis: Long = 0L,
        /**
         * 这批读数是**哪个房间**查出来的（`南校区 · 松园1号楼 · 1层 · 101`）。
         *
         * 不能直接用 base/floorLabel/roomLabel 拼：用户可能改了楼栋就退出，
         * 选择是新的、读数是旧的，拼起来会出现"新楼栋配旧电量"的错位。
         */
        val readingLocation: String = "",
    ) {
        /** 有读数可展示（首页卡用它决定显示电量还是引导） */
        val hasReading: Boolean get() = timeMillis > 0 && (lightKwh != null || acKwh != null)

        /** 选择完整，可以在首页一键刷新 */
        val canRefresh: Boolean get() = base.isNotBlank() && floorId.isNotBlank() && roomId.isNotBlank()

        /** 主读数：照明优先，没有照明就看空调（只装空调的楼栋） */
        val primaryKwh: Double? get() = lightKwh ?: acKwh

        /** 首页卡副标题：优先用读数自带的位置，老数据回退到当前选择 */
        val displayLocation: String
            get() = readingLocation.ifBlank {
                listOf(campusLabel, base, floorLabel, roomLabel)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" · ")
            }
    }

    fun load(): Cached {
        val base = prefs.getString(K_BASE, "").orEmpty()
        val roomId = prefs.getString(K_ROOM_ID, "").orEmpty()
        val time = prefs.getLong(K_TIME, 0L)
        if (time == 0L) return Cached()

        // 老版本只存了「校区 + 房间名 + 一个电量」，没有可回放的编号：
        // 迁移成只读展示（roomLabel/lightKwh 有值但 canRefresh=false），用户下次查一次就补齐。
        // 老版本也没存 campus_key，只能从"北校区"这个中文标签反推，否则北校区用户会被误判成南区。
        val label = prefs.getString(K_CAMPUS_LABEL, "").orEmpty()
        val legacyRoom = prefs.getString(K_ROOM_LABEL_LEGACY, "").orEmpty()
        return Cached(
            campusKey = prefs.getString(K_CAMPUS_KEY, null)
                ?: if (label.contains("北")) SdcxElectricityClient.KEY_NORTH else SdcxElectricityClient.KEY_SOUTH,
            campusLabel = label.ifBlank { "南校区" },
            base = base,
            floorId = prefs.getString(K_FLOOR_ID, "").orEmpty(),
            floorLabel = prefs.getString(K_FLOOR_LABEL, "").orEmpty(),
            roomId = roomId,
            roomLabel = prefs.getString(K_ROOM_LABEL, "").orEmpty().ifBlank { legacyRoom },
            lightKwh = prefs.getFloat(K_LIGHT, prefs.getFloat(K_KWH_LEGACY, Float.NaN))
                .let { if (it.isNaN()) null else it.toDouble() },
            acKwh = prefs.getFloat(K_AC, Float.NaN).let { if (it.isNaN()) null else it.toDouble() },
            timeMillis = time,
            readingLocation = prefs.getString(K_READ_LOCATION, "").orEmpty(),
        )
    }

    /** 记住选择（用户在页面上选完楼栋/楼层/房间时立刻落盘，即便还没查询） */
    fun saveSelection(
        campusKey: String,
        campusLabel: String,
        base: String,
        floorId: String,
        floorLabel: String,
        roomId: String,
        roomLabel: String,
    ) {
        prefs.edit()
            .putString(K_CAMPUS_KEY, campusKey)
            .putString(K_CAMPUS_LABEL, campusLabel)
            .putString(K_BASE, base)
            .putString(K_FLOOR_ID, floorId)
            .putString(K_FLOOR_LABEL, floorLabel)
            .putString(K_ROOM_ID, roomId)
            .putString(K_ROOM_LABEL, roomLabel)
            .apply()
    }

    /** 记住读数（查询成功后调用，首页卡立刻能显示） */
    fun saveReading(result: SdcxElectricityClient.QueryResult, location: String) {
        val light = result.readings.firstOrNull { it.meter == SdcxParser.Meter.LIGHT }?.kwh
        val ac = result.readings.firstOrNull { it.meter == SdcxParser.Meter.AC }?.kwh
        prefs.edit()
            .putFloat(K_LIGHT, light?.toFloat() ?: Float.NaN)
            .putFloat(K_AC, ac?.toFloat() ?: Float.NaN)
            .putString(K_READ_LOCATION, location)
            .putLong(K_TIME, System.currentTimeMillis())
            .apply()
    }

    companion object {
        private const val PREFS = "electricity_cache"
        private const val K_CAMPUS_KEY = "campus_key"
        private const val K_CAMPUS_LABEL = "campus_label"
        private const val K_BASE = "base"
        private const val K_FLOOR_ID = "floor_id"
        private const val K_FLOOR_LABEL = "floor_label"
        private const val K_ROOM_ID = "room_id"
        private const val K_ROOM_LABEL = "room_label"
        private const val K_LIGHT = "light_kwh"
        private const val K_AC = "ac_kwh"
        private const val K_READ_LOCATION = "read_location"
        private const val K_TIME = "time"

        /** 旧版字段，仅用于迁移展示 */
        private const val K_ROOM_LABEL_LEGACY = "room"
        private const val K_KWH_LEGACY = "kwh"
    }
}
