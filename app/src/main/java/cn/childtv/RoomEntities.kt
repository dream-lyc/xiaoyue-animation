package cn.childtv

import androidx.room.*

@Entity(tableName = "sources", indices = [Index("type")])
data class SourceEntity(@PrimaryKey val id: String, val name: String, val type: String, val endpoint: String, val credentialConfigured: Boolean, val deleted: Boolean = false, @ColumnInfo(defaultValue = "'legacy'") val authMode: String = "legacy")
@Entity(tableName = "source_roots", primaryKeys = ["sourceId", "path"], foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.NO_ACTION)])
data class SourceRootEntity(val sourceId: String, val path: String)
@Entity(tableName = "libraries", indices = [Index(value = ["name"], unique = true)])
data class LibraryEntity(@PrimaryKey val id: String, val name: String, val deleted: Boolean = false)
@Entity(tableName = "series", foreignKeys = [ForeignKey(entity = LibraryEntity::class, parentColumns = ["id"], childColumns = ["libraryId"], onDelete = ForeignKey.NO_ACTION)], indices = [Index("libraryId"), Index(value = ["libraryId", "identity"], unique = true)])
data class SeriesEntity(@PrimaryKey val id: String, val libraryId: String, val title: String, val deleted: Boolean = false, val identity: String? = null)
@Entity(tableName = "seasons", foreignKeys = [ForeignKey(entity = SeriesEntity::class, parentColumns = ["id"], childColumns = ["seriesId"], onDelete = ForeignKey.NO_ACTION)], indices = [Index("seriesId"), Index(value = ["seriesId", "number"], unique = true)])
data class SeasonEntity(@PrimaryKey val id: String, val seriesId: String, val number: Int)
@Entity(tableName = "episodes", foreignKeys = [ForeignKey(entity = SeasonEntity::class, parentColumns = ["id"], childColumns = ["seasonId"], onDelete = ForeignKey.NO_ACTION)], indices = [Index("seasonId"), Index(value = ["seasonId", "number"])])
data class EpisodeEntity(@PrimaryKey val id: String, val seasonId: String, val number: Int, val title: String, val synopsis: String, val artworkUrl: String?, val status: String, val available: Boolean, val deleted: Boolean, val lockedFieldsJson: String, val extraJson: String)
@Entity(tableName = "media_assets", foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.NO_ACTION)], indices = [Index("sourceId"), Index(value = ["sourceId", "path"], unique = true)])
data class MediaAssetEntity(@PrimaryKey val id: String, val sourceId: String, val path: String, val size: Long, val stableId: String?, val available: Boolean)
@Entity(tableName = "episode_assets", primaryKeys = ["episodeId", "assetId"], foreignKeys = [ForeignKey(entity = EpisodeEntity::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.NO_ACTION), ForeignKey(entity = MediaAssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.NO_ACTION)], indices = [Index("assetId")])
data class EpisodeAssetEntity(val episodeId: String, val assetId: String, val startMs: Long, val endMs: Long?)
@Entity(tableName = "tag_keys", indices = [Index(value = ["name"], unique = true)])
data class TagKeyEntity(@PrimaryKey val id: String, val name: String)
@Entity(tableName = "tag_values", primaryKeys = ["keyId", "value"], foreignKeys = [ForeignKey(entity = TagKeyEntity::class, parentColumns = ["id"], childColumns = ["keyId"], onDelete = ForeignKey.NO_ACTION)])
data class TagValueEntity(val keyId: String, val value: String)
@Entity(tableName = "episode_tags", primaryKeys = ["episodeId", "keyId", "value"], foreignKeys = [ForeignKey(entity = EpisodeEntity::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.NO_ACTION), ForeignKey(entity = TagValueEntity::class, parentColumns = ["keyId", "value"], childColumns = ["keyId", "value"], onDelete = ForeignKey.NO_ACTION)], indices = [Index(value = ["keyId", "value"])])
data class EpisodeTagEntity(val episodeId: String, val keyId: String, val value: String)
@Entity(tableName = "watch_intervals", foreignKeys = [ForeignKey(entity = EpisodeEntity::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.NO_ACTION)], indices = [Index("episodeId"), Index("date"), Index(value = ["libraryId", "date"]), Index(value = ["seriesId", "date"])])
data class WatchIntervalEntity(@PrimaryKey val id: String, val episodeId: String, val libraryId: String, val libraryName: String, val seriesId: String, val seriesTitle: String, val title: String, val startedAt: String, val date: String, val durationMillis: Long, val kind: String)
@Entity(tableName = "progress", foreignKeys = [ForeignKey(entity = EpisodeEntity::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.NO_ACTION)])
data class ProgressEntity(@PrimaryKey val episodeId: String, val positionMs: Long, val completed: Boolean = false)
@Entity(tableName = "daily_policies")
data class DailyPolicyEntity(@PrimaryKey val day: String, val totalSeconds: Long, val watchedSeconds: Long, val overrideSeconds: Long?, val promptHandled: Boolean, val mode: String, val finishingJson: String?, val extraEpisodeId: String?, val extraProgressJson: String?)
@Entity(tableName = "scan_jobs", foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.NO_ACTION)], indices = [Index("sourceId"), Index("status")])
data class ScanJobEntity(@PrimaryKey val id: String, val sourceId: String, val status: String, val stage: String, val discovered: Int, val matched: Int, val completed: Int, val error: String?, val cursorJson: String? = null)

@Entity(tableName = "field_overrides", primaryKeys = ["episodeId", "field"], foreignKeys = [ForeignKey(entity = EpisodeEntity::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.NO_ACTION)])
data class FieldOverrideEntity(val episodeId: String, val field: String, val valueJson: String)
@Entity(tableName = "metadata_matches", primaryKeys = ["episodeId", "candidateId"], foreignKeys = [ForeignKey(entity = EpisodeEntity::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.NO_ACTION)])
data class MetadataMatchEntity(val episodeId: String, val candidateId: String, val identity: String, val title: String, val seasonNumber: Int, val episodeNumber: Int, val evidenceJson: String, @ColumnInfo(defaultValue = "''") val seriesTitle: String = "")
@Entity(tableName = "artwork", foreignKeys = [ForeignKey(entity = EpisodeEntity::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.NO_ACTION)], indices = [Index("episodeId")])
data class ArtworkEntity(@PrimaryKey val id: String, val episodeId: String, val uri: String, val origin: String, val cacheKey: String? = null)
@Entity(tableName = "default_policy")
data class DefaultPolicyEntity(@PrimaryKey val id: String = "family", val totalSeconds: Long, val timezone: String)

@Dao interface CatalogDao {
    @Query("SELECT * FROM default_policy WHERE id='family'") fun defaultPolicy(): DefaultPolicyEntity?
    @Query("SELECT * FROM field_overrides WHERE episodeId=:id ORDER BY field") fun overrides(id: String): List<FieldOverrideEntity>
    @Query("DELETE FROM field_overrides WHERE episodeId=:id") fun clearOverrides(id: String)
    @Query("SELECT * FROM metadata_matches WHERE episodeId=:id ORDER BY candidateId") fun candidates(id: String): List<MetadataMatchEntity>
    @Query("DELETE FROM metadata_matches WHERE episodeId=:id") fun clearCandidates(id: String)
    @Query("SELECT * FROM artwork WHERE episodeId=:id ORDER BY id") fun artwork(id: String): List<ArtworkEntity>
    @Query("DELETE FROM artwork WHERE episodeId=:id") fun clearArtwork(id: String)
    @Upsert fun put(value: DefaultPolicyEntity)
    @Upsert fun put(value: FieldOverrideEntity)
    @Upsert fun put(value: MetadataMatchEntity)
    @Upsert fun put(value: ArtworkEntity)
    @Query("SELECT * FROM episodes WHERE id=:id") fun episode(id: String): EpisodeEntity?
    @Query("SELECT * FROM seasons WHERE id=:id") fun season(id: String): SeasonEntity?
    @Query("SELECT * FROM series WHERE id=:id") fun series(id: String): SeriesEntity?
    @Query("SELECT * FROM libraries WHERE id=:id") fun library(id: String): LibraryEntity?
    @Query("SELECT * FROM sources WHERE id=:id") fun source(id: String): SourceEntity?
    @Query("SELECT * FROM media_assets WHERE id=:id") fun asset(id: String): MediaAssetEntity?
    @Query("SELECT * FROM watch_intervals WHERE id=:id") fun watch(id: String): WatchIntervalEntity?
    @Query("SELECT * FROM progress WHERE episodeId=:id") fun progress(id: String): ProgressEntity?
    @Query("SELECT * FROM sources ORDER BY id") fun sources(): List<SourceEntity>
    @Query("SELECT * FROM source_roots WHERE sourceId=:id ORDER BY path") fun roots(id: String): List<SourceRootEntity>
    @Query("DELETE FROM source_roots WHERE sourceId=:id") fun clearRoots(id: String)
    @Query("SELECT * FROM libraries ORDER BY id") fun libraries(): List<LibraryEntity>
    @Query("SELECT * FROM series ORDER BY id") fun series(): List<SeriesEntity>
    @Query("SELECT * FROM seasons ORDER BY id") fun seasons(): List<SeasonEntity>
    @Query("SELECT * FROM episodes ORDER BY id") fun episodes(): List<EpisodeEntity>
    @Query("SELECT * FROM media_assets ORDER BY id") fun assets(): List<MediaAssetEntity>
    @Query("SELECT * FROM episode_assets ORDER BY episodeId,assetId") fun mappings(): List<EpisodeAssetEntity>
    @Query("SELECT * FROM tag_keys ORDER BY id") fun keys(): List<TagKeyEntity>
    @Query("SELECT * FROM tag_values WHERE keyId=:id ORDER BY value") fun values(id: String): List<TagValueEntity>
    @Query("SELECT * FROM episode_tags WHERE episodeId=:id ORDER BY keyId,value") fun tags(id: String): List<EpisodeTagEntity>
    @Query("DELETE FROM episode_tags WHERE episodeId=:id") fun clearEpisodeTags(id: String)
    @Query("DELETE FROM tag_values WHERE keyId=:id") fun clearValues(id: String)
    @Query("DELETE FROM tag_keys WHERE id=:id") fun deleteKey(id: String)
    @Query("DELETE FROM episode_assets WHERE episodeId=:episodeId AND assetId=:assetId") fun deleteMapping(episodeId: String, assetId: String)
    @Query("SELECT * FROM watch_intervals ORDER BY id") fun watches(): List<WatchIntervalEntity>
    @Query("SELECT * FROM progress ORDER BY episodeId") fun progress(): List<ProgressEntity>
    @Query("SELECT * FROM daily_policies ORDER BY day") fun daily(): List<DailyPolicyEntity>
    @Query("SELECT * FROM scan_jobs ORDER BY id") fun scans(): List<ScanJobEntity>
    @Upsert fun put(value: SourceEntity)
    @Upsert fun put(value: SourceRootEntity)
    @Upsert fun put(value: LibraryEntity)
    @Upsert fun put(value: SeriesEntity)
    @Upsert fun put(value: SeasonEntity)
    @Upsert fun put(value: EpisodeEntity)
    @Upsert fun put(value: MediaAssetEntity)
    @Upsert fun put(value: EpisodeAssetEntity)
    @Upsert fun put(value: TagKeyEntity)
    @Upsert fun put(value: TagValueEntity)
    @Upsert fun put(value: EpisodeTagEntity)
    @Upsert fun put(value: WatchIntervalEntity)
    @Upsert fun put(value: ProgressEntity)
    @Upsert fun put(value: DailyPolicyEntity)
    @Upsert fun put(value: ScanJobEntity)
}
