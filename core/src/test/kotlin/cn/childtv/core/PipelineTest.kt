package cn.childtv.core
import kotlin.test.*
class PipelineTest {
 @Test fun serviceThrottleExposesRetryDeadlineToPersistentScanScheduler() {
  val source=object:SourceAdapter {override fun testConnection()=false;override fun listDirectories(path:String,page:String?)=error("unused");override fun listFiles(path:String,page:String?)=error("unused");override fun stat(path:String)=error("unused");override fun openRange(path:String,offset:Long,length:Int)=ByteArray(length);override fun resolvePlayback(path:String)=error("unused")}
  val hash=object:MovieHashProvider {override val configured=true;override fun search(movieHash:String,size:Long):List<MetadataCandidate> = throw MetadataServiceException("限流",1234)}
  val result=MetadataPipeline(hash).match(source,SourceFile("/unknown.mp4",size=200000));assertEquals(1234,result.retryAtMillis)
 }

 @Test fun seriesNfoOwnsSeriesIdentityWhileEpisodeNfoOwnsEpisodeFields() {
  val source=object:SourceAdapter {override fun testConnection()=false;override fun listDirectories(path:String,page:String?)=error("unused");override fun listFiles(path:String,page:String?)=error("unused");override fun stat(path:String)=error("unused");override fun openRange(path:String,offset:Long,length:Int)=error("unused");override fun resolvePlayback(path:String)=error("unused")}
  val episode=NfoParser.parse("<episodedetails><title>泥坑</title><showtitle>小猪佩奇</showtitle><season>1</season><episode>2</episode><uniqueid type=\"tmdb\">999</uniqueid></episodedetails>")
  val series=NfoParser.parse("<tvshow><title>小猪佩奇</title><uniqueid type=\"tmdb\">44</uniqueid></tvshow>")
  val result=MetadataPipeline().match(source,SourceFile("/Season 1/episode.mp4",size=200000),nfo=episode,seriesNfo=series)
  assertEquals(MatchStatus.MATCHED,result.status);assertEquals("tmdb:44",result.fields.values["identity"]);assertEquals("小猪佩奇",result.fields.values["seriesTitle"]);assertEquals("泥坑",result.fields.values["title"])
 }

 @Test fun confirmedEpisodeNumberOverridesConflictingFilenameEvidence() {
  val source=object:SourceAdapter {override fun testConnection()=false;override fun listDirectories(path:String,page:String?)=error("unused");override fun listFiles(path:String,page:String?)=error("unused");override fun stat(path:String)=error("unused");override fun openRange(path:String,offset:Long,length:Int)=error("unused");override fun resolvePlayback(path:String)=error("unused")}
  val result=MetadataPipeline().match(source,SourceFile("/wrong S01E01.mp4",size=200000),confirmedIdentity="tmdb:44",confirmedNumber=EpisodeNumber(2,3))
  assertEquals(MatchStatus.MATCHED,result.status);assertEquals(EpisodeNumber(2,3),result.candidates.single().number)
 }

 @Test fun automaticMatchingCombinesLocalAndHashEvidenceAndProtectsManualTitle() {
  var ranges=0
  val source=object:SourceAdapter {
   override fun testConnection()=true;override fun listDirectories(path:String,page:String?)=DirectoryPage(emptyList());override fun listFiles(path:String,page:String?)=DirectoryPage(emptyList());override fun stat(path:String)=SourceFile(path,size=131072)
   override fun openRange(path:String,offset:Long,length:Int):ByteArray {ranges++;return ByteArray(length)}
   override fun resolvePlayback(path:String)=PlaybackLocation(java.net.URI("http://example.com/video"))
  }
  val hash=object:MovieHashProvider {override val configured=true;override fun search(movieHash:String,size:Long)=listOf(MetadataCandidate("tmdb:1",EpisodeNumber(1,2),EvidenceKind.MOVIE_HASH,true))}
  val r=MetadataPipeline(hash).match(source,SourceFile("/show S01E02.mp4",size=131072),manual=MetadataFields(mapOf("title" to "家长标题"),setOf("title")))
  assertEquals("tmdb:1",r.fields.values["identity"]);assertEquals("1",r.fields.values["seasonNumber"]);assertEquals("2",r.fields.values["episodeNumber"]);
  assertEquals(MatchStatus.MATCHED,r.status);assertEquals(2,ranges);assertEquals("家长标题",r.fields.values["title"])
 }
 @Test fun unavailableHashRetainsUnmatchedFileWithDiagnostic() {
  val source=object:SourceAdapter {override fun testConnection()=false;override fun listDirectories(path:String,page:String?)=error("unused");override fun listFiles(path:String,page:String?)=error("unused");override fun stat(path:String)=error("unused");override fun openRange(path:String,offset:Long,length:Int)=throw SourceException(SourceFailure.RANGE_UNSUPPORTED);override fun resolvePlayback(path:String)=error("unused")}
  val result=MetadataPipeline().match(source,SourceFile("/unknown.mp4",size=131072));assertEquals(MatchStatus.UNMATCHED,result.status);assertTrue(result.diagnostics.contains("自动资料服务未配置"))
 }
}
