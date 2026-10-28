package cn.childtv.core
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader

data class EpisodeNumber(val season:Int?,val episode:Int)
object FilenameParser {
 fun seriesName(path:String):String? {
  val parts=path.replace('\\','/').split('/').filter{it.isNotBlank()}
  val season=Regex("(?i)(season[ ._-]*\\d+|s\\d{1,3}|第[零〇一二两三四五六七八九十百\\d]+季)")
  parts.dropLast(1).asReversed().firstOrNull{!season.matches(it)}?.let{return it}
  var name=parts.lastOrNull()?.substringBeforeLast('.')?:return null
  name=name.replace(Regex("(?i)S\\d{1,3}E\\d{1,4}.*$"),"").replace(Regex("第[零〇一二两三四五六七八九十百\\d]+[集话話].*$"),"")
  name=name.replace(Regex("(?i)(?:^|[ ._-])(?:480p|720p|1080p|2160p|x26[45]|h26[45]|HEVC|AVC|WEB[ ._-]?DL|BluRay|DVDRip)(?:[ ._-]|$).*$"),"")
  return name.replace(Regex("[._-]+")," ").trim().takeIf{it.isNotBlank()}
 }
 fun isMultiEpisode(name:String):Boolean = Regex("(?i)S\\d+E\\d+[ ._-]*E\\d+|S\\d+E\\d+[-_]+(?:S\\d+)?E?\\d+|合集|合辑").containsMatchIn(name)
 fun parse(name:String):EpisodeNumber? {
  Regex("(?i)S(\\d{1,3})E(\\d{1,4})(?!\\d)").find(name)?.let{return EpisodeNumber(it.groupValues[1].toInt(),it.groupValues[2].toInt())}
  Regex("第([零〇一二两三四五六七八九十百\\d]+)[集话話]").find(name)?.let{return EpisodeNumber(null,chineseNumber(it.groupValues[1]))}
  return null
 }
 private fun chineseNumber(s:String):Int {
  s.toIntOrNull()?.let{return it};var total=0;var current=0
  for(c in s){when(c){'十'->{total+=(if(current==0)1 else current)*10;current=0};'百'->{total+=(if(current==0)1 else current)*100;current=0};else->{current=when(c){'两'->2;'〇'->0;else->"零一二三四五六七八九".indexOf(c)};require(current>=0)}}};return total+current
 }
}
data class NfoMetadata(val title:String?,val plot:String?,val number:EpisodeNumber?,val externalIds:Map<String,String>,val artwork:List<String>,val seriesTitle:String?=null)
object NfoParser {
 fun parse(xml:String):NfoMetadata {
  require(xml.length<=2_000_000 && !xml.contains("<!DOCTYPE",true) && !xml.contains("<!ENTITY",true)){"不允许 NFO 外部实体"}
  val doc=parseLocalXml(xml)
  fun text(name:String):String? = doc.getElementsByTagName(name).item(0)?.textContent?.trim()?.takeIf{it.isNotEmpty()}
  val ids=linkedMapOf<String,String>();val nodes=doc.getElementsByTagName("uniqueid")
  for(i in 0 until nodes.length){val n=nodes.item(i);val type=n.attributes?.getNamedItem("type")?.nodeValue;if(!type.isNullOrBlank() && n.textContent.isNotBlank())ids[type]=n.textContent.trim()}
  val thumbs=doc.getElementsByTagName("thumb");val artwork=(0 until thumbs.length).map{thumbs.item(it).textContent.trim()}.filter{it.isNotEmpty()}
  return NfoMetadata(text("title"),text("plot"),text("episode")?.toIntOrNull()?.let{EpisodeNumber(text("season")?.toIntOrNull(),it)},ids,artwork,text("showtitle"))
 }
}
object MovieHash {
 fun compute(size:Long,head:ByteArray,tail:ByteArray):String {
  require(size>=131072 && head.size==65536 && tail.size==65536){"moviehash 需要至少 128 KiB 文件和首尾各 64 KiB"}
  var hash=size
  for(part in listOf(head,tail)){val buffer=ByteBuffer.wrap(part).order(ByteOrder.LITTLE_ENDIAN);while(buffer.hasRemaining())hash+=buffer.long}
  return java.lang.Long.toUnsignedString(hash,16).padStart(16,'0')
 }
}
enum class EvidenceKind { NFO_ID,CONFIRMED_ID,MOVIE_HASH,NAME }
enum class MatchStatus { MATCHED,PENDING,UNMATCHED }
data class MetadataCandidate(val identity:String,val number:EpisodeNumber?,val evidence:EvidenceKind,val trueHashMatch:Boolean=false,val title:String?=null,val seriesTitle:String?=null)
object MetadataMatcher {
 fun classify(candidates:List<MetadataCandidate>,parsed:EpisodeNumber?):MatchStatus {
  if(candidates.isEmpty())return MatchStatus.UNMATCHED
  if(candidates.map{it.identity to it.number}.distinct().size!=1)return MatchStatus.PENDING
  val c=candidates.first();if(parsed!=null && c.number!=null && ((parsed.season!=null && parsed.season!=c.number.season)||parsed.episode!=c.number.episode))return MatchStatus.PENDING
  return if(candidates.any{it.evidence in setOf(EvidenceKind.NFO_ID,EvidenceKind.CONFIRMED_ID) || (it.evidence==EvidenceKind.MOVIE_HASH && it.trueHashMatch)})MatchStatus.MATCHED else MatchStatus.PENDING
 }
}
data class MetadataFields(val values:Map<String,String>,val locked:Set<String> = emptySet()) {
 fun merge(incoming:Map<String,String>):MetadataFields = copy(values=values+incoming.filterKeys{it !in locked})
}
enum class ArtworkOrigin { LOCAL,ONLINE,VIDEO_FRAME,PLACEHOLDER }
data class Artwork(val uri:String,val origin:ArtworkOrigin,val episodeId:String?=null)
interface MetadataProvider {
 val configured:Boolean
 fun search(name:String,year:Int?=null,language:String="zh-CN"):List<MetadataCandidate>
 fun findByExternalId(provider:String,id:String):List<MetadataCandidate>
 fun series(identity:String,language:String="zh-CN"):MetadataFields = MetadataFields(emptyMap())
 fun episode(identity:String,number:EpisodeNumber,language:String="zh-CN"):MetadataFields
 fun artwork(identity:String,number:EpisodeNumber?):List<Artwork>
}
/** Search-only interface intentionally exposes no subtitle download operation. */
interface MovieHashProvider { val configured:Boolean; fun search(movieHash:String,size:Long):List<MetadataCandidate> }
