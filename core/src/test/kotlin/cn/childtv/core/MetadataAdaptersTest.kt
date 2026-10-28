package cn.childtv.core
import kotlin.test.*
import java.time.*
class MetadataAdaptersTest {
 @Test fun temporaryFailureRetriesAreBoundedAndHttpDateRetryAfterIsRespected() {
  var now=0L;var calls=0
  val transport=object:HttpTransport {override fun execute(request:HttpRequest):HttpResponse {calls++;return HttpResponse(503,if(calls==1) mapOf("Retry-After" to "Thu, 1 Jan 1970 00:00:03 GMT") else emptyMap(),ByteArray(0))}}
  val client=MetadataHttpClient(transport,{now});val request=HttpRequest("GET",java.net.URI("https://example.com/a"))
  assertEquals(3000,assertFailsWith<MetadataServiceException>{client.get("a",request)}.retryAtMillis)
  now=2999;assertFailsWith<MetadataServiceException>{client.get("a",request)};assertEquals(1,calls)
  now=3000;assertFailsWith<MetadataServiceException>{client.get("a",request)};now=5000;assertFailsWith<MetadataServiceException>{client.get("a",request)};now=100000
  assertFailsWith<MetadataServiceException>{client.get("a",request)};assertEquals(3,calls)
 }

 @Test fun adminGrantCannotBeReusedToChangeQuota() {val a=ParentAuthorization({0L},{lower,_->lower});val c=a.challenge(ParentAction.MANAGE_ADMIN);val token=a.answer(c.id,2)!!;assertFalse(a.consume(token,ParentAction.CHANGE_TODAY));assertTrue(a.consume(token,ParentAction.MANAGE_ADMIN))}
 @Test fun subtitlesProviderOnlySearchesAndRequiresTrueHashFlag() {
  val requested=mutableListOf<String>();val transport=object:HttpTransport{override fun execute(request:HttpRequest):HttpResponse{requested+=request.uri.toString();return HttpResponse(200,emptyMap(),"""{"data":[{"attributes":{"moviehash_match":true,"feature_details":{"feature_id":12,"tmdb_id":444,"parent_tmdb_id":44,"parent_title":"小猪佩奇","title":"泥坑","season_number":1,"episode_number":2}}},{"attributes":{"moviehash_match":false,"feature_details":{"feature_id":13,"tmdb_id":445,"parent_tmdb_id":45,"season_number":1,"episode_number":2}}}]}""".toByteArray())}}
  val p=OpenSubtitlesProvider("key",transport=transport);val matches=p.search("1234567890abcdef",200000)
  assertEquals(listOf(true,false),matches.map{it.trueHashMatch});assertEquals("tmdb:44",matches.first().identity);assertEquals("小猪佩奇",matches.first().seriesTitle);assertEquals("泥坑",matches.first().title);assertTrue(requested.single().contains("moviehash=1234567890abcdef"));assertFalse(requested.single().contains("download"))
 }
 @Test fun tmdbReadsLanguageEpisodeAndMissingArtworkWithoutFabricating() {
  val requested=mutableListOf<String>();val t=object:HttpTransport{override fun execute(request:HttpRequest):HttpResponse{requested+=request.uri.toString();val body=when{request.uri.path.endsWith("/tv/44")->"""{"name":"小猪佩奇","overview":"动画简介"}""";request.uri.path.endsWith("/images")->"""{"stills":[]}""";request.uri.path.endsWith("/search/tv")->"""{"results":[{"id":44}]}""";else->"""{"name":"Episode 2","overview":"plot"}"""};return HttpResponse(200,emptyMap(),body.toByteArray())}}
  val p=TmdbProvider("token",transport=t);assertEquals("tmdb:44",p.search("动画").single().identity);assertEquals("小猪佩奇",p.series("tmdb:44").values["seriesTitle"]);assertEquals("Episode 2",p.episode("tmdb:44",EpisodeNumber(1,2)).values["title"]);assertTrue(p.artwork("tmdb:44",EpisodeNumber(1,2)).isEmpty());assertTrue(requested.all{it.contains("language=zh-CN") || it.contains("include_image_language")})
  assertFalse(TmdbProvider("",transport=t).configured);assertFailsWith<MetadataServiceException>{TmdbProvider("",transport=t).search("x")}
 }
 @Test fun retryAfterIsHonoredAndResultsAreCached() {
  var now=0L;var calls=0;val t=object:HttpTransport{override fun execute(request:HttpRequest):HttpResponse {calls++;return if(calls==1)HttpResponse(429,mapOf("Retry-After" to "3"),ByteArray(0)) else HttpResponse(200,emptyMap(),"{}".toByteArray())}}
  val client=MetadataHttpClient(t,{now});val request=HttpRequest("GET",java.net.URI("https://example.com/a"))
  val fail=assertFailsWith<MetadataServiceException>{client.get("a",request)};assertEquals(3000,fail.retryAtMillis);assertFailsWith<MetadataServiceException>{client.get("a",request)};assertEquals(1,calls)
  now=3000;client.get("a",request);client.get("a",request);assertEquals(2,calls)
 }
}
