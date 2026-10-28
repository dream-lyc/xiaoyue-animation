package cn.childtv

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import java.util.UUID

object StorageMigrations {
    /** Also accepts early development v2 installs which predate cursor and artwork cache columns. */
    val FROM_2_TO_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            fun add(table: String, column: String, definition: String) {
                val exists = db.query("PRAGMA table_info(`$table`)").use { rows -> var found = false; while (rows.moveToNext()) if (rows.getString(rows.getColumnIndexOrThrow("name")) == column) found = true; found }
                if (!exists) db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
            }
            add("scan_jobs", "cursorJson", "TEXT")
            add("artwork", "cacheKey", "TEXT")
            add("metadata_matches", "seriesTitle", "TEXT NOT NULL DEFAULT ''")
            add("series", "identity", "TEXT")
            db.execSQL("DROP INDEX IF EXISTS index_series_libraryId_title")
            // Recover existing confirmed identities without merging two animations merely by display name.
            data class OldEpisode(val id: String, val seriesId: String, val season: Int, val identity: String?)
            val episodes = db.query("SELECT e.id,e.extraJson,s.seriesId,s.number FROM episodes e JOIN seasons s ON e.seasonId=s.id").use { rows -> buildList { while (rows.moveToNext()) add(OldEpisode(rows.getString(0), rows.getString(2), rows.getInt(3), JSONObject(rows.getString(1)).optString("confirmedIdentity").takeIf { it.isNotBlank() })) } }
            val groups = episodes.groupBy { it.seriesId }
            val known = mutableMapOf<Pair<String, String>, String>()
            groups.forEach { (oldId, children) ->
                val parent = db.query("SELECT libraryId,title,deleted FROM series WHERE id=?", arrayOf(oldId)).use { rows -> check(rows.moveToFirst()); Triple(rows.getString(0), rows.getString(1), rows.getInt(2)) }
                val identities = children.mapNotNull { it.identity }.distinct()
                identities.forEach { identity ->
                    val key = parent.first to identity
                    val canReuse = identities.size == 1 && children.all { it.identity == identity } && known[key] == null
                    val target = known.getOrPut(key) { if (canReuse) oldId else UUID.randomUUID().toString() }
                    if (target == oldId) db.execSQL("UPDATE series SET identity=? WHERE id=?", arrayOf(identity, oldId))
                    else {
                        db.execSQL("INSERT OR IGNORE INTO series(id,libraryId,title,deleted,identity) VALUES(?,?,?,?,?)", arrayOf<Any>(target, parent.first, parent.second, parent.third, identity))
                        children.filter { it.identity == identity }.forEach { child ->
                            val existing = db.query("SELECT id FROM seasons WHERE seriesId=? AND number=?", arrayOf<Any>(target, child.season)).use { rows -> if (rows.moveToFirst()) rows.getString(0) else null }
                            val seasonId = existing ?: UUID.randomUUID().toString().also { db.execSQL("INSERT INTO seasons(id,seriesId,number) VALUES(?,?,?)", arrayOf<Any>(it, target, child.season)) }
                            db.execSQL("UPDATE episodes SET seasonId=? WHERE id=?", arrayOf(seasonId, child.id))
                        }
                    }
                }
            }
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_series_libraryId_identity ON series(libraryId,identity)")
        }
    }
    val FROM_3_TO_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sources ADD COLUMN authMode TEXT NOT NULL DEFAULT 'legacy'")
        }
    }

}
