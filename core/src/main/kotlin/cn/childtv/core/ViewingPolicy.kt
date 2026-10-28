package cn.childtv.core

import java.time.LocalDate
import java.security.SecureRandom

enum class PolicyMode { AVAILABLE,FINISHING_CURRENT_EPISODE,LOCKED }
data class PlayingEpisode(val episodeId:String,val positionMs:Long,val endMs:Long) { init {require(positionMs>=0 && endMs>positionMs)} }
data class DailyState(val totalSeconds:Long,val watchedSeconds:Long=0,val overrideSeconds:Long?=null,val promptHandled:Boolean=false,val mode:PolicyMode=PolicyMode.AVAILABLE,val finishing:PlayingEpisode?=null,val extraEpisodeId:String?=null,val extraProgress:PlayingEpisode?=null)
class ViewingPolicyEngine(defaultSeconds:Long, initial:Map<LocalDate,DailyState> = emptyMap()) {
 var defaultSeconds=defaultSeconds; private set
 private val days=initial.toMutableMap()
 init {require(defaultSeconds>=0)}
 @Synchronized fun state(day:LocalDate):DailyState {
  val previous=days[day]?:DailyState(defaultSeconds)
  val total=previous.overrideSeconds?:defaultSeconds
  val mode=when { previous.extraEpisodeId!=null || previous.finishing!=null -> PolicyMode.FINISHING_CURRENT_EPISODE; previous.watchedSeconds<total -> PolicyMode.AVAILABLE;else->PolicyMode.LOCKED }
  return previous.copy(totalSeconds=total,mode=mode).also{days[day]=it}
 }
 @Synchronized fun snapshot():Map<LocalDate,DailyState> = days.toMap()
 @Synchronized fun remaining(day:LocalDate):Long = state(day).let{(it.totalSeconds-it.watchedSeconds).coerceAtLeast(0)}
 @Synchronized fun needsPrompt(day:LocalDate):Boolean = !state(day).promptHandled
 @Synchronized fun markPromptHandled(day:LocalDate){days[day]=state(day).copy(promptHandled=true)}
 @Synchronized fun setDefaultTotal(seconds:Long){require(seconds>=0);defaultSeconds=seconds;days.keys.toList().forEach{reevaluate(it,null)}}
 @Synchronized fun record(day:LocalDate,seconds:Long,current:PlayingEpisode?=null) {
  require(seconds>=0); val s=state(day); days[day]=s.copy(watchedSeconds=Math.addExact(s.watchedSeconds,seconds)); reevaluate(day,current)
 }
 @Synchronized fun setTodayTotal(day:LocalDate,seconds:Long?,current:PlayingEpisode?=null) {
  require(seconds==null || seconds>=0); days[day]=state(day).copy(overrideSeconds=seconds); reevaluate(day,current)
 }
 private fun reevaluate(day:LocalDate,current:PlayingEpisode?) {
  val s=state(day)
  days[day]=when { s.watchedSeconds<s.totalSeconds -> s.copy(mode=PolicyMode.AVAILABLE,finishing=null,extraEpisodeId=null,extraProgress=null)
   s.finishing!=null || s.extraEpisodeId!=null -> s
   current!=null -> s.copy(mode=PolicyMode.FINISHING_CURRENT_EPISODE,finishing=current)
   else -> s.copy(mode=PolicyMode.LOCKED) }
 }
 @Synchronized fun canStart(day:LocalDate,episodeId:String,positionMs:Long):Boolean {
  if(positionMs<0)return false; val s=state(day)
  return when {s.mode==PolicyMode.AVAILABLE->true;s.extraEpisodeId!=null->s.extraEpisodeId==episodeId && (s.extraProgress?.let{positionMs>=it.positionMs && positionMs<it.endMs}?:false);s.finishing!=null->s.finishing.let{it.episodeId==episodeId && positionMs>=it.positionMs && positionMs<it.endMs};else->false}
 }
 @Synchronized fun advance(day:LocalDate,episodeId:String,positionMs:Long) {
  val s=state(day); val f=s.finishing?:s.extraProgress?:return
  require(f.episodeId==episodeId && positionMs>=f.positionMs)
  if(positionMs>=f.endMs)finish(day,episodeId) else days[day]=if(s.extraEpisodeId!=null)s.copy(extraProgress=f.copy(positionMs=positionMs)) else s.copy(finishing=f.copy(positionMs=positionMs))
 }
 @Synchronized fun finish(day:LocalDate,episodeId:String) {
  val s=state(day)
  if(s.finishing?.episodeId==episodeId || s.extraEpisodeId==episodeId) {days[day]=s.copy(finishing=null,extraEpisodeId=null,extraProgress=null);state(day)}
 }
 /** Caller must first consume a parent grant bound to EXTRA_EPISODE. */
 @Synchronized fun allowExtra(day:LocalDate,episodeId:String){allowExtra(day,PlayingEpisode(episodeId,0,Long.MAX_VALUE))}
 @Synchronized fun allowExtra(day:LocalDate,episode:PlayingEpisode){require(episode.episodeId.isNotBlank());days[day]=state(day).copy(finishing=null,extraEpisodeId=episode.episodeId,extraProgress=episode,mode=PolicyMode.FINISHING_CURRENT_EPISODE)}
}
enum class ParentAction { CHANGE_TODAY,EXTRA_EPISODE,MANAGE_ADMIN }
data class ParentChallenge(val id:String,val prompt:String)
class ParentAuthorization(private val nowMillis:()->Long={System.nanoTime()/1_000_000},private val random:(Int,Int)->Int={a,b->a+SecureRandom().nextInt(b-a)}) {
 private data class Challenge(val public:ParentChallenge,val answer:Int,val action:ParentAction,val expires:Long)
 private data class Grant(val action:ParentAction,val expires:Long)
 private var current:Challenge?=null;private var failures=0;private var blockedUntil=Long.MIN_VALUE
 private val grants=mutableMapOf<String,Grant>()
 @Synchronized fun challenge(action:ParentAction):ParentChallenge {
  val now=nowMillis();check(now>=blockedUntil){"请等待 30 秒后重试"}
  grants.clear()
  val operation=random(0,3)
  val a=when(operation){0->random(1,20);1->random(1,21);else->random(1,10)}
  val b=when(operation){0->random(1,21-a);1->random(0,a+1);else->random(1,10)}
  val operator=when(operation){0->"+";1->"-";else->"×"}
  val answer=when(operation){0->a+b;1->a-b;else->a*b}
  val public=ParentChallenge(newId(),"$a $operator $b = ?")
  current=Challenge(public,answer,action,now+120_000);return public
 }
 @Synchronized fun answer(id:String,answer:Int):String? {
  val now=nowMillis();if(now<blockedUntil)return null
  val c=current?:return null;if(c.public.id!=id || now>=c.expires)return null
  current=null
  if(answer!=c.answer){failures++;if(failures>=3){blockedUntil=now+30_000;failures=0};return null}
  failures=0; val token=newId();grants[token]=Grant(c.action,now+120_000);return token
 }
 @Synchronized fun consume(token:String,action:ParentAction):Boolean {
  val g=grants[token]?:return false
  if(nowMillis()>=g.expires){grants.remove(token);return false}
  if(g.action!=action)return false
  grants.remove(token);return true
 }
 @Synchronized fun cancel(token:String?=null){current=null;if(token!=null)grants.remove(token) else grants.clear()}
}
