package cn.childtv.core
import java.time.*
import kotlin.math.abs

enum class WatchKind { NORMAL,GRACE,PARENT_EXTRA }
data class WatchInterval(val id:String,val episodeId:String,val libraryId:String,val seriesId:String,val startUtc:Instant,val endUtc:Instant,val day:LocalDate,val zoneId:String,val durationMillis:Long,val kind:WatchKind) {
 init {require(durationMillis>0 && endUtc>startUtc)}
}
class PlaybackClock(private val sessionId:String,private val episodeId:String,private val libraryId:String,private val seriesId:String,private val zone:ZoneId) {
 private var lastMono:Long?=null;private var lastWall:Instant?=null;private var wasPlaying=false;private var oldKind=WatchKind.NORMAL;private var sequence=0L
 var clockAnomaly=false;private set
 @Synchronized fun update(monotonicMs:Long,wall:Instant,playing:Boolean,kind:WatchKind):List<WatchInterval> {
  val mono=lastMono;val previous=lastWall;val intervals=mutableListOf<WatchInterval>()
  if(mono!=null && previous!=null){
   require(monotonicMs>=mono){"单调时钟回拨；新进程必须建立新会话"}
   val delta=monotonicMs-mono;val expected=previous.plusMillis(delta)
   if(abs(Duration.between(expected,wall).toMillis())>2000)clockAnomaly=true
   if(wasPlaying && delta>0){
    var cursor:Instant=previous;val end=expected
    while(cursor<end){val day=cursor.atZone(zone).toLocalDate();val midnight=day.plusDays(1).atStartOfDay(zone).toInstant();val until=if(midnight<end)midnight else end
     intervals+=WatchInterval("$sessionId:${sequence++}",episodeId,libraryId,seriesId,cursor,until,day,zone.id,Duration.between(cursor,until).toMillis(),oldKind);cursor=until
    }
   }
   lastWall=if(clockAnomaly)expected else wall
  } else lastWall=wall
  lastMono=monotonicMs;wasPlaying=playing;oldKind=kind;return intervals
 }
}
data class ViewingSummary(val durationMillis:Long,val graceMillis:Long,val parentExtraMillis:Long,val episodeCount:Int,val watchingDays:Int,val daily:Map<LocalDate,Long>)
class ViewingAnalytics(initial:Collection<WatchInterval> = emptyList()) {
 private val events=linkedMapOf<String,WatchInterval>()
 init {initial.forEach{append(it)}}
 @Synchronized fun append(interval:WatchInterval):Boolean {
  val previous=events[interval.id];require(previous==null || previous==interval){"相同事件 ID 的内容冲突"}
  if(previous!=null)return false;events[interval.id]=interval;return true
 }
 @Synchronized fun snapshot():List<WatchInterval> = events.values.toList()
 @Synchronized fun details(day:LocalDate):List<WatchInterval> = events.values.filter{it.day==day}.sortedBy{it.startUtc}
 @Synchronized fun summary(from:LocalDate?=null,to:LocalDate?=null,libraryId:String?=null,seriesId:String?=null,tags:TagCatalog?=null,selection:Map<String,Set<String>> = emptyMap()):ViewingSummary {
  require(selection.isEmpty() || tags!=null)
  val selected=events.values.filter{(from==null || it.day>=from) && (to==null || it.day<=to) && (libraryId==null || it.libraryId==libraryId) && (seriesId==null || it.seriesId==seriesId) && (selection.isEmpty() || tags!!.filter(setOf(it.episodeId),selection).isNotEmpty())}
  return ViewingSummary(selected.sumOf{it.durationMillis},selected.filter{it.kind==WatchKind.GRACE}.sumOf{it.durationMillis},selected.filter{it.kind==WatchKind.PARENT_EXTRA}.sumOf{it.durationMillis},selected.map{it.episodeId}.distinct().size,selected.map{it.day}.distinct().size,selected.groupBy{it.day}.mapValues{(_,v)->v.sumOf{it.durationMillis}}.toSortedMap())
 }
}
