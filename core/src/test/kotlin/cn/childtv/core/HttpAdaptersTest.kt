package cn.childtv.core
import kotlin.test.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
class HttpAdaptersTest {
 private fun server(block:(String)->Unit) {val s=HttpServer.create(InetSocketAddress("127.0.0.1",0),0);s.createContext("/"){x->
  val path=x.requestURI.path
  val payload=when(path){
   "/api/fs/list"->"""{"code":200,"data":{"content":[{"name":"Peppa S01E01.mp4","size":200000,"is_dir":false},{"name":"Season 1","size":0,"is_dir":true}],"total":201}}"""
   "/api/fs/get"->"""{"code":200,"data":{"name":"Peppa S01E01.mp4","size":200000,"is_dir":false,"raw_url":"http://127.0.0.1:${s.address.port}/video"}}"""
   "/video"->"abcd"
   "/unauthorized/api/fs/list"->"""{"code":401,"message":"secret token"}"""
   else->"""<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response><d:response><d:href>/dav/hello%20world.mp4</d:href><d:propstat><d:prop><d:getcontentlength>200000</d:getcontentlength><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
  }
  if(path=="/video") {assertEquals("bytes=10-13",x.requestHeaders.getFirst("Range"));x.responseHeaders.add("Content-Range","bytes 10-13/200000")}
  x.sendResponseHeaders(if(path=="/video")206 else if(x.requestMethod=="PROPFIND")207 else 200,payload.toByteArray().size.toLong());x.responseBody.use{it.write(payload.toByteArray())}
 };s.start();try{block("http://127.0.0.1:${s.address.port}")}finally{s.stop(0)}}
 @Test fun openListPagesListsResolvesAndOnlyReadsRequestedRange()=server{base->
  val a=OpenListAdapter(base,"do-not-log");val page=a.listFiles("/");assertEquals("2",page.nextPage);assertEquals("/Peppa S01E01.mp4",page.files.first().path)
  assertEquals(1,a.listDirectories("/").files.size);assertEquals(200000,a.stat("/Peppa S01E01.mp4").size)
  assertEquals("abcd",a.openRange("/Peppa S01E01.mp4",10,4).toString(Charsets.UTF_8))
  val error=assertFailsWith<SourceException>{OpenListAdapter("$base/unauthorized","secret token").testConnection()};assertEquals(SourceFailure.AUTHENTICATION,error.reason);assertFalse(error.message!!.contains("secret"))
 }
 @Test fun webDavUsesPropfindAndDecodesPaths()=server{base->val a=WebDavAdapter("$base/dav/","u","p");val files=a.listFiles("/");assertEquals(1,files.files.size);assertEquals("/hello world.mp4",files.files.single().path);assertEquals(200000,files.files.single().size)}
 @Test fun rejectedRangeDoesNotDownloadEntireVideo() {
  val t=object:HttpTransport {override fun execute(request:HttpRequest):HttpResponse = if(request.uri.path.endsWith("/get"))HttpResponse(200,emptyMap(),"""{"code":200,"data":{"name":"a","size":200000,"raw_url":"http://example.com/video"}}""".toByteArray()) else HttpResponse(200,emptyMap(),ByteArray(65536))}
  assertFailsWith<SourceException>{OpenListAdapter("http://example.com",transport=t).openRange("/a",0,65536)}
 }
}
