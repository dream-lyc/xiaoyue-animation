package cn.childtv.core
import kotlin.test.*
class MaintenanceTest {
 @Test fun seasonMappingPreviewsThenAtomicallyCreatesLogicalEpisodesAndRanges() {
  val c=LibraryCatalog();c.libraries["l"]=Library("l","片库");c.series["s"]=Series("s","l","动画");c.seasons["season"]=Season("season","s",1)
  val preview=c.previewMapping("season",listOf("asset1","asset2"),3);assertEquals(listOf(3,4),preview.map{it.episodeNumber});assertTrue(c.episodes.isEmpty())
  val ids=c.applyMapping(preview);assertEquals(2,ids.size);assertEquals(2,c.mappings.size)
  assertEquals(ids,c.applyMapping(preview));assertEquals(2,c.episodes.size);assertEquals(2,c.mappings.size)
  assertFailsWith<IllegalArgumentException>{c.applyMapping(preview+MappingPreview("missing",9,"asset3"))};assertEquals(2,c.episodes.size)
 }
 @Test fun renumberingIsAtomicAndDoesNotChangeLogicalIdentity() {
  val c=LibraryCatalog();c.seasons["s"]=Season("s","series",1);c.episodes["a"]=Episode("a","s",1,"a");c.episodes["b"]=Episode("b","s",2,"b")
  assertFailsWith<IllegalArgumentException>{c.renumber(mapOf("a" to 2))};assertEquals(1,c.episodes["a"]!!.number)
  c.renumber(mapOf("a" to 2,"b" to 1));assertEquals(2,c.episodes["a"]!!.number);assertEquals("a",c.episodes["a"]!!.id)
 }
}
