package cn.childtv.core

import kotlin.test.*
import java.time.*

class DomainTest {
 @Test fun tagsAreAtomicAndOrWithinKeyAndAcrossKeys() {
  val tags=TagCatalog(); tags.initializeDefaults(); tags.initializeDefaults()
  assertEquals(setOf("常规一","常规二","常规三"),tags.dictionary["庆爸路线"])
  tags.apply(setOf("a","b"),"庆爸路线",setOf("常规一"),TagOperation.APPEND)
  tags.apply(setOf("a"),"庆爸路线",setOf("常规二"),TagOperation.APPEND)
  tags.apply(setOf("a","c"),"语言",setOf("英语"),TagOperation.APPEND)
  assertEquals(setOf("a"),tags.filter(setOf("a","b","c"),mapOf("庆爸路线" to setOf("常规一","常规二"),"语言" to setOf("英语"))))
  assertFailsWith<IllegalArgumentException>{tags.apply(setOf("a"),"语言",setOf(""),TagOperation.REPLACE)}
  assertEquals(setOf("英语"),tags.values("a","语言"))
  tags.apply(setOf("a"),"庆爸路线",setOf("常规三"),TagOperation.REPLACE)
  assertEquals(setOf("常规三"),tags.values("a","庆爸路线")); assertTrue(tags.values("new","庆爸路线").isEmpty())
 }
 @Test fun policyKeepsConsumptionAndFinishingBoundaries() {
  val day=LocalDate.of(2026,10,27); val engine=ViewingPolicyEngine(2700)
  engine.record(day,1200); engine.setTodayTotal(day,3600)
  assertEquals(2400,engine.remaining(day)); engine.setTodayTotal(day,600,PlayingEpisode("e",120_000,600_000))
  assertEquals(PolicyMode.FINISHING_CURRENT_EPISODE,engine.state(day).mode)
  assertTrue(engine.canStart(day,"e",120_000)); assertFalse(engine.canStart(day,"other",120_000)); assertFalse(engine.canStart(day,"e",119_000))
  engine.advance(day,"e",300_000); assertFalse(engine.canStart(day,"e",200_000))
  engine.finish(day,"e"); assertEquals(PolicyMode.LOCKED,engine.state(day).mode)
  assertEquals(2700,engine.remaining(day.plusDays(1))); assertEquals(0,engine.remaining(day))
 }
 @Test fun pausedSettingsRetainCurrentButExitedPlayerCannotCreateGrace() {
  val d=LocalDate.now(); val e=ViewingPolicyEngine(60); e.record(d,60)
  e.setTodayTotal(d,30); assertEquals(PolicyMode.LOCKED,e.state(d).mode)
  e.setTodayTotal(d,30,PlayingEpisode("a",10,100)); assertTrue(e.canStart(d,"a",10))
  e.markPromptHandled(d); assertFalse(e.needsPrompt(d)); assertTrue(e.needsPrompt(d.plusDays(1)))
 }
 @Test fun parentChallengeIsOneUseActionBoundAndRateLimited() {
  var now=0L; val auth=ParentAuthorization({now},{lower,_->lower})
  repeat(3){val c=auth.challenge(ParentAction.CHANGE_TODAY); assertNull(auth.answer(c.id,-1))}
  assertFailsWith<IllegalStateException>{auth.challenge(ParentAction.EXTRA_EPISODE)}
  now=30_000; val c=auth.challenge(ParentAction.EXTRA_EPISODE); val token=auth.answer(c.id,2)!!
  assertFalse(auth.consume(token,ParentAction.CHANGE_TODAY)); assertTrue(auth.consume(token,ParentAction.EXTRA_EPISODE)); assertFalse(auth.consume(token,ParentAction.EXTRA_EPISODE))
  val second=auth.challenge(ParentAction.CHANGE_TODAY); val expired=auth.answer(second.id,2)!!; now+=120_000; assertFalse(auth.consume(expired,ParentAction.CHANGE_TODAY))
 }
 @Test fun clockUsesActualMonotonicTimeSplitsMidnightAndDeduplicates() {
  val zone=ZoneId.of("Asia/Shanghai"); val start=Instant.parse("2026-10-27T15:59:58Z")
  val clock=PlaybackClock("session","e","lib","series",zone)
  clock.update(0,start,true,WatchKind.NORMAL); val spans=clock.update(5000,start.plusSeconds(5),false,WatchKind.NORMAL)
  assertEquals(listOf(2000L,3000L),spans.map{it.durationMillis}); assertEquals(2,spans.map{it.day}.distinct().size)
  assertTrue(clock.update(10000,start.plusSeconds(10),false,WatchKind.NORMAL).isEmpty())
  val ledger=ViewingAnalytics(); spans.forEach{ledger.append(it);ledger.append(it)}
  assertEquals(5000,ledger.summary().durationMillis); assertEquals(1,ledger.summary().episodeCount)
  val tags=TagCatalog(); tags.apply(setOf("e"),"route",setOf("one","two"),TagOperation.APPEND)
  assertEquals(5000,ledger.summary(tags=tags,selection=mapOf("route" to setOf("one","two"))).durationMillis)
 }
 @Test fun filenameNfoAndMovieHashDoNotInventIdentity() {
  assertEquals(EpisodeNumber(2,3),FilenameParser.parse("小猪 S02E03.mp4")); assertEquals(EpisodeNumber(null,12),FilenameParser.parse("小猪 第十二集.mkv")); assertNull(FilenameParser.parse("unknown.mp4"))
  val nfo=NfoParser.parse("<episodedetails><title>甲</title><season>2</season><episode>3</episode><uniqueid type=\"tmdb\">45</uniqueid></episodedetails>")
  assertEquals("甲",nfo.title); assertEquals("45",nfo.externalIds["tmdb"])
  assertFails{NfoParser.parse("<!DOCTYPE x [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><episodedetails><title>&xxe;</title></episodedetails>")}
  assertEquals("0000000000020000",MovieHash.compute(131072,ByteArray(65536),ByteArray(65536)))
  assertFailsWith<IllegalArgumentException>{MovieHash.compute(12,ByteArray(12),ByteArray(12))}
 }
 @Test fun scansOnlyMarkMissingOnSuccessfulCompleteTraversal() {
  val catalog=AssetCatalog(); val a=SourceFile("/a.mp4","stable",200000); val b=SourceFile("/b.mp4",null,200000)
  val initial=catalog.reconcile("s","/",listOf(a,b),true); assertEquals(2,initial.size)
  val again=catalog.reconcile("s","/",listOf(a.copy(path="/moved.mp4")),false)
  assertEquals(initial.first().id,again.first().id); assertTrue(catalog.assets.values.all{it.available})
  catalog.reconcile("s","/",listOf(a.copy(path="/moved.mp4")),true)
  assertFalse(catalog.assets.values.single{it.path=="/b.mp4"}.available)
 }
 @Test fun metadataProtectsOverridesAndRejectsConflictingCandidates() {
  val fields=MetadataFields(mapOf("title" to "人工"),setOf("title")); assertEquals("人工",fields.merge(mapOf("title" to "网络","plot" to "说明")).values["title"])
  val a=MetadataCandidate("tmdb:1",EpisodeNumber(1,2),EvidenceKind.NFO_ID)
  assertEquals(MatchStatus.MATCHED,MetadataMatcher.classify(listOf(a),EpisodeNumber(1,2)))
  assertEquals(MatchStatus.PENDING,MetadataMatcher.classify(listOf(a,a.copy(identity="tmdb:2")),EpisodeNumber(1,2)))
  assertEquals(MatchStatus.PENDING,MetadataMatcher.classify(listOf(a.copy(evidence=EvidenceKind.NAME)),EpisodeNumber(1,2)))
  assertEquals(MatchStatus.UNMATCHED,MetadataMatcher.classify(emptyList(),null))
 }
}
