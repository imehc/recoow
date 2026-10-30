package imehc.recoow.core.data.statistics

import imehc.recoow.core.database.DatabaseHost
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.statistics.StatisticsRepository
import imehc.recoow.core.data.history.historySources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

class RoomStatisticsRepository(private val host: DatabaseHost) : StatisticsRepository {
    override fun dates(route: ToolRoute): Flow<List<Long>> {
        val source = historySources.firstOrNull { it.route == route }
        val table = source?.table ?: if (route == ToolRoute.REMINDERS) "reminders" else "food_entries"
        val column = source?.time ?: if (route == ToolRoute.REMINDERS) "scheduled_at" else "occurred_at"
        return host.observe { db -> db.invalidationTracker.createFlow(table).map {
            host.access { current -> current.openHelper.readableDatabase.query("SELECT $column FROM $table WHERE deleted_at IS NULL").use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0)) }
            } }
        } }.distinctUntilChanged().flowOn(Dispatchers.IO)
    }
}
