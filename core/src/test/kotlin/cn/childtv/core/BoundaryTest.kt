package cn.childtv.core
import kotlin.test.*
import java.time.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
class BoundaryTest {
 @Test fun raisedDefaultReleasesGraceWithoutChangingDailyOverride() {
  val d=LocalDate.of(2026,10,27);val e=ViewingPolicyEngine(60);e.record(d,60,PlayingEpisode("a",1,100));e.setDefaultTotal(120)
  assertEquals(PolicyMode.AVAILABLE,e.state(d).mode);assertNull(e.state(d).finishing)
  e.setTodayTotal(d,30);e.setDefaultTotal(300);assertEquals(30,e.state(d).totalSeconds)
 }
 @Test fun movieHashMatchesOfficialBreakdanceVectorAndWraps64BitOverflow() {
  val head=java.io.File("src/test/resources/oshash/breakdance-head.bin").readBytes();val tail=java.io.File("src/test/resources/oshash/breakdance-tail.bin").readBytes()
  assertEquals("8e245d9679d31e12",MovieHash.compute(12909756,head,tail))
  val block=ByteArray(65536){0xff.toByte()};assertEquals("00000000ffffc001",MovieHash.compute(4294967297,block,block))
 }
 @Test fun scanRootDoesNotDeleteSiblingOrMergeNameAndFingerprint() {
  val c=AssetCatalog();c.reconcile("s","/a",listOf(SourceFile("/a/x.mp4",null,100)),true);c.reconcile("s","/ab",listOf(SourceFile("/ab/x.mp4",null,100)),true)
  c.reconcile("s","/a",emptyList(),true);assertTrue(c.assets.values.single{it.path=="/ab/x.mp4"}.available)
 }
}
