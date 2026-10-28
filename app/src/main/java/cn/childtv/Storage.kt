package cn.childtv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

@Entity(tableName = "documents", primaryKeys = ["collection", "id"])
data class Document(val collection: String, val id: String, val json: String)
@Dao interface Documents {
    @Query("SELECT * FROM documents WHERE collection=:collection ORDER BY id") fun list(collection: String): List<Document>
    @Query("SELECT * FROM documents WHERE collection=:collection AND id=:id") fun get(collection: String, id: String): Document?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(document: Document)
    @Query("DELETE FROM documents WHERE collection=:collection AND id=:id") fun delete(collection: String, id: String)
    @Query("DELETE FROM documents") fun clear()
}
@Database(entities = [Document::class, SourceEntity::class, SourceRootEntity::class, LibraryEntity::class, SeriesEntity::class, SeasonEntity::class, EpisodeEntity::class, MediaAssetEntity::class, EpisodeAssetEntity::class, TagKeyEntity::class, TagValueEntity::class, EpisodeTagEntity::class, WatchIntervalEntity::class, ProgressEntity::class, DailyPolicyEntity::class, ScanJobEntity::class, FieldOverrideEntity::class, MetadataMatchEntity::class, ArtworkEntity::class, DefaultPolicyEntity::class], version = 4, exportSchema = true, autoMigrations = [AutoMigration(from = 1, to = 2)])
abstract class ChildDatabase : RoomDatabase() { abstract fun documents(): Documents; abstract fun catalog(): CatalogDao }

/** A single Room transaction boundary serves TV and HTTP; typed tables own catalog and counters. */
class LocalStore(context: Context, databaseName: String = "child-tv.db") {
    internal val artworkWork = ArtworkWorkState()
    val catalogEpoch = java.util.concurrent.atomic.AtomicLong(0)
    val artworkDirectory = java.io.File(context.filesDir, if (databaseName == "child-tv.db") "artwork" else "$databaseName.artwork")
    val database = Room.databaseBuilder(context, ChildDatabase::class.java, databaseName).addMigrations(StorageMigrations.FROM_2_TO_3, StorageMigrations.FROM_3_TO_4).build()
    private val dao = database.documents()
    private val catalog = TypedCatalog(database)
    @Synchronized fun list(collection: String): List<JSONObject> = if (collection in catalog.collections) catalog.list(collection) else dao.list(collection).map { JSONObject(it.json) }
    @Synchronized fun get(collection: String, id: String): JSONObject? = if (collection in catalog.collections) catalog.get(collection, id) else dao.get(collection, id)?.let { JSONObject(it.json) }
    @Synchronized fun put(collection: String, value: JSONObject): JSONObject = transaction {
        if (!value.has("id")) value.put("id", UUID.randomUUID().toString())
        if (collection in catalog.collections) catalog.put(collection, value) else dao.put(Document(collection, value.getString("id"), value.toString()))
        value
    }
    @Synchronized fun delete(collection: String, id: String) = transaction { if (collection in catalog.collections) catalog.delete(collection, id) else dao.delete(collection, id) }
    @Synchronized fun <T> transaction(block: () -> T): T = database.runInTransaction(java.util.concurrent.Callable { block() })
    fun getSetting(key: String, fallback: String): String {
        val policy = if (key in listOf("defaultMinutes", "timezone")) database.catalog().defaultPolicy() else null
        return when { key == "defaultMinutes" && policy != null -> (policy.totalSeconds / 60).toString(); key == "timezone" && policy != null -> policy.timezone; else -> get("settings", key)?.optString("value", fallback) ?: fallback }
    }
    fun setSetting(key: String, value: String) { transaction {
        if (key in listOf("defaultMinutes", "timezone")) {
            val old = database.catalog().defaultPolicy() ?: DefaultPolicyEntity(totalSeconds = 2700, timezone = "Asia/Shanghai")
            database.catalog().put(if (key == "defaultMinutes") old.copy(totalSeconds = value.toLong() * 60) else old.copy(timezone = value))
        } else put("settings", JSONObject().put("id", key).put("value", value))
    } }
    /** Restores the catalog in FK order. A failure rolls back every deletion and insertion. */
    @Synchronized fun replaceAll(content: Map<String, List<JSONObject>>) = transaction {
        catalogEpoch.incrementAndGet()
        catalog.clear()
        val order = listOf("sources", "assets", "tags", "library", "episode-assets", "watch", "progress", "daily-state", "scans")
        order.forEach { collection -> content[collection].orEmpty().forEach { put(collection, it) } }
        content.filterKeys { it !in catalog.collections }.forEach { (collection, rows) -> dao.list(collection).forEach { dao.delete(collection, it.id) }; rows.forEach { put(collection, it) } }
    }
    init {
        try { transaction {
            // Version 1 stored JSON documents; migrate every identity transactionally once.
            if (dao.get("settings", "typed-storage-v2") == null) {
                val order = listOf("sources", "assets", "tags", "library", "episode-assets", "watch", "progress", "daily-state", "scans")
                order.forEach { collection -> dao.list(collection).forEach { row -> catalog.put(collection, JSONObject(row.json)); dao.delete(collection, row.id) } }
                dao.put(Document("settings", "typed-storage-v2", JSONObject().put("id", "typed-storage-v2").put("value", "true").toString()))
            }
            if (database.catalog().defaultPolicy() == null) {
                val minutes = dao.get("settings", "defaultMinutes")?.let { JSONObject(it.json).optString("value", "45").toLong() } ?: 45L
                val timezone = dao.get("settings", "timezone")?.let { JSONObject(it.json).optString("value", "Asia/Shanghai") } ?: "Asia/Shanghai"
                database.catalog().put(DefaultPolicyEntity(totalSeconds = minutes * 60, timezone = timezone)); dao.delete("settings", "defaultMinutes"); dao.delete("settings", "timezone")
            }
            if (list("tags").isEmpty()) put("tags", JSONObject().put("id", "qingba").put("key", "庆爸路线").put("values", JSONArray(listOf("常规一", "常规二", "常规三"))))
        } } catch (failure: Exception) { database.close(); throw failure }
    }
}

class CredentialVault(context: Context, namespace: String = "encrypted-credentials", private val alias: String = "child-tv.credentials.v1") {
    companion object { private val keyLock = Any() }
    private val preferences = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private fun key(): javax.crypto.SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? javax.crypto.SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun put(id: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString(id, Base64.getEncoder().encodeToString(data)).commit())
    }
    fun get(id: String): String? {
        val encoded = preferences.getString(id, null) ?: return null
        val data = Base64.getDecoder().decode(encoded)
        return Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12))); String(doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8) }
    }
}
fun List<JSONObject>.jsonArray() = JSONArray(this)
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
