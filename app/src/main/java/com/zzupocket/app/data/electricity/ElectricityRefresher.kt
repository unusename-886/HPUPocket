package com.zzupocket.app.data.electricity

import com.zzupocket.app.data.prefs.ElectricityPrefsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 首页电量卡的「一键刷新」：只用记住的选择重放一次查询，不需要用户进页面。
 *
 * 回放只需要 `home.viewState + 楼层号 + 房间号`——`queryRoom` 内部会自己补每个表计的
 * 楼栋/楼层两次回发，所以这里不必再走三级联动。
 */
class ElectricityRefresher(
    private val prefs: ElectricityPrefsStore,
    private val south: SdcxElectricityClient,
    private val north: SdcxElectricityClient,
) {
    /** @return 新读数；没记住选择 / 楼栋已不存在 / 查询无数据时返回 null */
    suspend fun refresh(): SdcxElectricityClient.QueryResult? = withContext(Dispatchers.IO) {
        val cached = prefs.load()
        if (!cached.canRefresh) return@withContext null
        val client = if (cached.campusKey == SdcxElectricityClient.KEY_SOUTH) south else north
        runCatching {
            val home = client.loadHome()
            val group = home.buildings.firstOrNull { it.base == cached.base } ?: return@runCatching null
            client.queryRoom(group, cached.floorId, cached.roomId, home.viewState)
                .takeIf { it.hasData }
                ?.also {
                    prefs.saveReading(
                        it,
                        listOf(cached.campusLabel, cached.base, cached.floorLabel, cached.roomLabel)
                            .filter { label -> label.isNotBlank() }
                            .distinct()
                            .joinToString(" · "),
                    )
                }
        }.getOrNull()
    }
}
