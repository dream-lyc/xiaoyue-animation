package cn.childtv.core
import com.google.gson.JsonObject
import java.net.URI
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class MetadataServiceException(val reason:String,val retryAtMillis:Long?=null):Exception(reason)
class MetadataHttpClient(private val transport:HttpTransport=OkHttpTransport(),private val nowMillis:()->Long=System::currentTimeMillis) {
 private data class Cached(val data:JsonObject,val expires:Long)
 private data class Failure(val attempts:Int,val retryAt:Long)
 private val cache=linkedMapOf<String,Cached>();private val failures=mutableMapOf<String,Failure>()
 @Synchronized fun get(key:String,request:HttpRequest):JsonObject {
  val now=nowMillis();cache[key]?.takeIf{now<it.expires}?.let{return it.data.deepCopy()}
  val previous=failures[key]
  if(previous!=null && (now<previous.retryAt || previous.attempts>=3))throw MetadataServiceException(if(previous.attempts>=3)"重试已达上限，请手动重试" else "资料服务等待重试",previous.retryAt)
  val response=try{transport.execute(request)}catch(e:SourceException){return fail(key,previous,now,null)}
  if(response.status==429 || response.status in 500..599)return fail(key,previous,now,response.header("Retry-After"))
  if(response.status in setOf(401,403))throw MetadataServiceException("资料服务凭据无效")
  if(response.status !in 200..299)throw MetadataServiceException("资料服务请求失败 (${response.status})")
  val json=try{jsonResponse(response)}catch(e:SourceException){throw MetadataServiceException("资料服务响应无效")}
  failures.remove(key);cache.remove(key);cache[key]=Cached(json,now+86_400_000);while(cache.size>512)cache.remove(cache.keys.first())
  return json.deepCopy()
 }
 private fun fail(key:String,previous:Failure?,now:Long,retryAfter:String?):Nothing {
  val attempts=(previous?.attempts?:0)+1
  val serverDelay=retryAfter?.toLongOrNull()?.coerceAtLeast(0)?.coerceAtMost(86_400)?.times(1000)
   ?:retryAfter?.let{runCatching{(ZonedDateTime.parse(it,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-now).coerceAtLeast(0)}.getOrNull()}
  val retry=now+(serverDelay?:((1L shl (attempts-1).coerceAtMost(6))*1000));failures[key]=Failure(attempts,retry)
  throw MetadataServiceException("资料服务限流或暂时不可用",retry)
 }
 @Synchronized fun retry(key:String){failures.remove(key)}
}
internal fun queryUri(base:URI,path:String,params:Map<String,String>):URI = URI(base.toString().trimEnd('/')+path+params.entries.joinToString("&",prefix=if(params.isEmpty())"" else "?"){(k,v)->URLEncoder.encode(k,"UTF-8")+"="+URLEncoder.encode(v,"UTF-8")})
class OpenSubtitlesProvider(private val apiKey:String,private val userAgent:String="ChildTV v0.1",transport:HttpTransport=OkHttpTransport(),endpoint:String="https://api.opensubtitles.com/api/v1"):MovieHashProvider {
 private val client=MetadataHttpClient(transport);private val base=safeEndpoint(endpoint)
 override val configured:Boolean get()=apiKey.isNotBlank()
 override fun search(movieHash:String,size:Long):List<MetadataCandidate> {
  if(!configured)throw MetadataServiceException("自动资料服务未配置")
  require(movieHash.matches(Regex("[a-f0-9]{16}")) && size>=131072)
  val uri=queryUri(base,"/subtitles",mapOf("moviehash" to movieHash,"moviehash_match" to "include"));val json=client.get(uri.toString(),HttpRequest("GET",uri,mapOf("Api-Key" to apiKey,"User-Agent" to userAgent)))
  return json.getAsJsonArray("data")?.mapNotNull{ item -> val attributes=item.asJsonObject.getAsJsonObject("attributes")?:return@mapNotNull null;val details=attributes.getAsJsonObject("feature_details")?:return@mapNotNull null
   val number=details.long("episode_number")?.toInt()?.let{EpisodeNumber(details.long("season_number")?.toInt(),it)}
   val tmdb=if(number!=null)details.long("parent_tmdb_id") else details.long("tmdb_id")
   val identity=if(tmdb!=null && tmdb>0)"tmdb:$tmdb" else details.long("feature_id")?.let{"opensubtitles:$it"}?:return@mapNotNull null
   MetadataCandidate(identity,number,EvidenceKind.MOVIE_HASH,attributes.boolean("moviehash_match"),details.string("title"),details.string("parent_title"))
  }?.distinct().orEmpty()
 }
}
class TmdbProvider(private val accessToken:String,transport:HttpTransport=OkHttpTransport(),endpoint:String="https://api.themoviedb.org/3"):MetadataProvider {
 private val base=safeEndpoint(endpoint);private val client=MetadataHttpClient(transport)
 override val configured:Boolean get()=accessToken.isNotBlank()
 private fun get(path:String,params:Map<String,String> = emptyMap()):JsonObject {
  if(!configured)throw MetadataServiceException("自动资料服务未配置")
  val uri=queryUri(base,path,params);return client.get(uri.toString(),HttpRequest("GET",uri,mapOf("Authorization" to "Bearer $accessToken","Accept" to "application/json")))
 }
 private fun id(identity:String):String {require(identity.matches(Regex("tmdb:[0-9]+")));return identity.substringAfter(':')}
 override fun search(name:String,year:Int?,language:String):List<MetadataCandidate> {
  val params=mutableMapOf("query" to name,"language" to language,"include_adult" to "false");if(year!=null)params["first_air_date_year"]=year.toString()
  return get("/search/tv",params).getAsJsonArray("results")?.mapNotNull{it.asJsonObject.long("id")?.let{id->MetadataCandidate("tmdb:$id",null,EvidenceKind.NAME,title=it.asJsonObject.string("name"),seriesTitle=it.asJsonObject.string("name"))}}.orEmpty()
 }
 override fun findByExternalId(provider:String,id:String):List<MetadataCandidate> {
  require(provider in setOf("imdb_id","tvdb_id","wikidata_id"));require(id.matches(Regex("[A-Za-z0-9]+")))
  val json=get("/find/$id",mapOf("external_source" to provider,"language" to "zh-CN"));val result=mutableListOf<MetadataCandidate>()
  json.getAsJsonArray("tv_results")?.forEach{it.asJsonObject.long("id")?.let{id->result+=MetadataCandidate("tmdb:$id",null,EvidenceKind.CONFIRMED_ID)}}
  json.getAsJsonArray("tv_episode_results")?.forEach{v->val e=v.asJsonObject;val show=e.long("show_id");val number=e.long("episode_number");if(show!=null && number!=null)result+=MetadataCandidate("tmdb:$show",EpisodeNumber(e.long("season_number")?.toInt(),number.toInt()),EvidenceKind.CONFIRMED_ID)}
  return result
 }
 override fun series(identity:String,language:String):MetadataFields {
  val data=get("/tv/${id(identity)}",mapOf("language" to language))
  return MetadataFields(buildMap { data.string("name")?.let{put("seriesTitle",it)};data.string("first_air_date")?.let{put("firstAirDate",it)} })
 }
 override fun episode(identity:String,number:EpisodeNumber,language:String):MetadataFields {
  require(number.season!=null);val data=get("/tv/${id(identity)}/season/${number.season}/episode/${number.episode}",mapOf("language" to language))
  return MetadataFields(buildMap{data.string("name")?.let{put("title",it)};data.string("overview")?.let{put("plot",it)};data.string("still_path")?.let{put("stillPath",it)}})
 }
 override fun artwork(identity:String,number:EpisodeNumber?):List<Artwork> {
  val path=if(number==null)"/tv/${id(identity)}/images" else {require(number.season!=null);"/tv/${id(identity)}/season/${number.season}/episode/${number.episode}/images"}
  val data=get(path,mapOf("include_image_language" to "zh,en,null"));return data.getAsJsonArray(if(number==null)"posters" else "stills")?.mapNotNull{it.asJsonObject.string("file_path")?.let{p->Artwork("https://image.tmdb.org/t/p/w780$p",ArtworkOrigin.ONLINE)}}.orEmpty()
 }
}
