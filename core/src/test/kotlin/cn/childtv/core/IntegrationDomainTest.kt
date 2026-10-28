package cn.childtv.core
import kotlin.test.*
import java.time.*
class IntegrationDomainTest {
 @Test fun stateSnapshotsPreserveDailyUseAndConsumedExtraEpisode() {
  val d=LocalDate.of(2026,10,27); val e=ViewingPolicyEngine(60); e.record(d,60)
  e.allowExtra(d,"next"); assertTrue(e.canStart(d,"next",0)); assertFalse(e.canStart(d,"other",0))
  e.finish(d,"next"); val restored=ViewingPolicyEngine(60,e.snapshot()); assertFalse(restored.canStart(d,"next",0)); assertEquals(60,restored.state(d).watchedSeconds)
 }
 @Test fun statsUseViewingLibraryAndCurrentTagsAndIncludeRewatch() {
  val ledger=ViewingAnalytics(); val d=LocalDate.of(2026,10,27); val start=Instant.parse("2026-10-27T00:00:00Z")
  ledger.append(WatchInterval("a","e","old","s",start,start.plusSeconds(600),d,"Asia/Shanghai",600000,WatchKind.NORMAL))
  ledger.append(WatchInterval("b","e","old","s",start.plusSeconds(1000),start.plusSeconds(1300),d,"Asia/Shanghai",300000,WatchKind.GRACE))
  assertEquals(900000,ledger.summary(libraryId="old").durationMillis); assertEquals(0,ledger.summary(libraryId="new").durationMillis)
  val tags=TagCatalog(); assertEquals(0,ledger.summary(tags=tags,selection=mapOf("路线" to setOf("一"))).durationMillis)
  tags.apply(setOf("e"),"路线",setOf("一"),TagOperation.APPEND)
  val summary=ledger.summary(tags=tags,selection=mapOf("路线" to setOf("一"))); assertEquals(900000,summary.durationMillis); assertEquals(300000,summary.graceMillis); assertEquals(1,summary.watchingDays)
 }
 @Test fun clockIgnoresWallClockRollbackForElapsedTimeAndSplitsKinds() {
  val start=Instant.parse("2026-10-27T00:00:00Z"); val c=PlaybackClock("s","e","l","a",ZoneId.of("UTC"))
  c.update(100,start,true,WatchKind.NORMAL)
  val normal=c.update(2100,start.minusSeconds(600),true,WatchKind.GRACE); assertEquals(2000,normal.sumOf{it.durationMillis}); assertEquals(WatchKind.NORMAL,normal.single().kind)
  val grace=c.update(3100,start.minusSeconds(599),false,WatchKind.GRACE); assertEquals(1000,grace.sumOf{it.durationMillis}); assertEquals(WatchKind.GRACE,grace.single().kind)
  assertTrue(c.clockAnomaly)
 }
 @Test fun episodeBoundariesAndLibrarySelectionsAreExplicit() {
  val c=LibraryCatalog(); c.libraries["l"]=Library("l","启蒙"); c.series["s"]=Series("s","l","动画"); c.seasons["season"]=Season("season","s",1)
  c.episodes["e"]=Episode("e","season",1,"第一集")
  assertEquals(setOf("e"),c.select(seriesId="s")); assertEquals(setOf("e"),c.select(seasonId="season"))
  assertFailsWith<IllegalArgumentException>{EpisodeAsset("e","a",20,10)}
  assertEquals(100,EpisodeAsset("e","a",100,200).durationMs)
 }
}
