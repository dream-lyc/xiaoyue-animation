package cn.childtv.core
import kotlin.test.*
class NamingCorpusTest {
 @Test fun nameSearchSkipsSeasonDirectoriesAndStripsOnlyExplicitReleaseMarkers() {
  assertEquals("Peppa Pig",FilenameParser.seriesName("/Peppa Pig/Season 1/S01E02.mp4"))
  assertEquals("小猪佩奇",FilenameParser.seriesName("/小猪佩奇/第1季/第十二集.mp4"))
  assertEquals("Peppa Pig",FilenameParser.seriesName("/Peppa.Pig.S01E02.1080p.WEB-DL.mkv"))
  assertEquals("weekend video",FilenameParser.seriesName("/weekend_video.mp4"))
  assertTrue(FilenameParser.isMultiEpisode("Peppa S01E01-E02.mp4"));assertFalse(FilenameParser.isMultiEpisode("Peppa S01E01 x265.mp4"))
 }
 @Test fun deterministicSyntheticCorpusRecordsParsingWithoutClaimingOnlineHitRates() {
  val corpus=listOf("小猪佩奇 第十二集.mp4" to EpisodeNumber(null,12),"Alphablocks.S02E03.1080p.mkv" to EpisodeNumber(2,3),"Peppa.Pig.S01E01.x265.国语.mkv" to EpisodeNumber(1,1),"wrong-weekend-video.mp4" to null)
  corpus.forEach{(name,expected)->val result=FilenameParser.parse(name);assertEquals(expected,result);println("SYNTHETIC_CORPUS $name -> $result")}
 }
}
