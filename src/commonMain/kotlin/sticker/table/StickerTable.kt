package sticker.table

import neton.database.api.DbContext
import neton.database.api.Row

/** All persistence uses the framework execution gateway, including transaction-bound calls. */
class StickerTable(val db: DbContext) {
    suspend fun rows(sql: String, vararg params: Pair<String, Any?>): List<Row> = db.fetchAll(sql, mapOf(*params))
    suspend fun execute(sql: String, vararg params: Pair<String, Any?>): Long = db.execute(sql, mapOf(*params))
    suspend fun lock(key: String) { rows("SELECT 1 AS ok FROM pg_advisory_xact_lock(hashtext(:key))", "key" to "sticker:$key") }
    suspend fun pack(id: Long) = rows("SELECT * FROM sticker_packs WHERE id=:id", "id" to id).firstOrNull()
    suspend fun author(id: Long) = rows("SELECT * FROM sticker_authors WHERE id=:id", "id" to id).firstOrNull()
    suspend fun resource(id: Long) = rows("SELECT * FROM sticker_resources WHERE id=:id", "id" to id).firstOrNull()
}
