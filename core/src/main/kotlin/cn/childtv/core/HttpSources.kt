package cn.childtv.core

import java.net.URI
import java.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.w3c.dom.Element
import com.google.gson.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

enum class SourceFailure { AUTHENTICATION,NOT_FOUND,NETWORK,INVALID_RESPONSE,RANGE_UNSUPPORTED,RATE_LIMITED }
class SourceException(val reason:SourceFailure,val status:Int?=null):IOException("资源请求失败：$reason${status?.let{" ($it)"}.orEmpty()}")
class HttpRequest(val method:String,val uri:URI,val headers:Map<String,String> = emptyMap(),val body:ByteArray?=null,val maxBytes:Int=4*1024*1024,val requirePartial:Boolean=false)
class HttpResponse(val status:Int,val headers:Map<String,String>,val body:ByteArray) {
 fun header(name:String):String?=headers.entries.firstOrNull{it.key.equals(name,true)}?.value
}
interface HttpTransport {fun execute(request:HttpRequest):HttpResponse}
class OkHttpTransport:HttpTransport {
 private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).followRedirects(true).followSslRedirects(false).build()
 override fun execute(request:HttpRequest):HttpResponse {
  require(request.uri.scheme in setOf("http","https") && request.uri.userInfo==null)
  val builder=Request.Builder().url(request.uri.toString());request.headers.forEach{(k,v)->builder.header(k,v)}
  val body=request.body?.toRequestBody((request.headers["Content-Type"]?:"application/octet-stream").toMediaType())
  builder.method(request.method,body)
  try {client.newCall(builder.build()).execute().use { response ->
   val headers=response.headers.names().associateWith{response.header(it).orEmpty()}
   // A range-ignoring server must never stream a complete video into memory.
   if(request.requirePartial && response.code!=206)throw SourceException(SourceFailure.RANGE_UNSUPPORTED,response.code)
   val out=ByteArrayOutputStream();response.body?.byteStream()?.use { input -> val buffer=ByteArray(8192);while(true){val read=input.read(buffer);if(read<0)break;if(out.size()+read>request.maxBytes)throw SourceException(SourceFailure.INVALID_RESPONSE,response.code);out.write(buffer,0,read)} }
   return HttpResponse(response.code,headers,out.toByteArray())
  }}catch(e:SourceException){throw e}catch(e:IOException){throw SourceException(SourceFailure.NETWORK)}
 }
}
internal fun checkResponse(response:HttpResponse) {
 if(response.status in 200..299)return
 throw SourceException(when(response.status){401,403->SourceFailure.AUTHENTICATION;404->SourceFailure.NOT_FOUND;429->SourceFailure.RATE_LIMITED;else->SourceFailure.NETWORK},response.status)
}
internal fun jsonResponse(response:HttpResponse):JsonObject {checkResponse(response);try{return JsonParser.parseString(response.body.toString(Charsets.UTF_8)).asJsonObject}catch(e:RuntimeException){throw SourceException(SourceFailure.INVALID_RESPONSE)}}
internal fun JsonObject.string(key:String):String? = get(key)?.takeUnless{it.isJsonNull}?.asString
internal fun JsonObject.long(key:String):Long? = get(key)?.takeUnless{it.isJsonNull}?.asLong
internal fun JsonObject.boolean(key:String):Boolean = get(key)?.takeUnless{it.isJsonNull}?.asBoolean?:false
internal fun safeEndpoint(endpoint:String):URI = URI(endpoint.trimEnd('/')).also{require(it.scheme in setOf("http","https") && it.host!=null && it.userInfo==null && it.query==null && it.fragment==null)}
internal fun pathJoin(root:String,name:String):String {require(name!=".." && !name.contains('/'));return root.trimEnd('/')+"/"+name}
internal fun exactRange(transport:HttpTransport,location:PlaybackLocation,offset:Long,length:Int):ByteArray {
 require(offset>=0 && length in 1..1_048_576);val end=Math.addExact(offset,length.toLong()-1)
 val response=transport.execute(HttpRequest("GET",location.uri,location.headers+mapOf("Range" to "bytes=$offset-$end","Accept-Encoding" to "identity"),maxBytes=length,requirePartial=true))
 checkResponse(response)
 if(response.status!=206 || response.body.size!=length || response.header("Content-Range")?.startsWith("bytes $offset-$end/")!=true)throw SourceException(SourceFailure.RANGE_UNSUPPORTED,response.status)
 return response.body
}
class OpenListAdapter(endpoint:String,private val token:String="",private val directoryPassword:String="",private val transport:HttpTransport=OkHttpTransport(),private val username:String="",private val password:String?=null,private val clientId:String="child-tv"):SourceAdapter {
 private val endpoint=safeEndpoint(endpoint)
 private fun validateHeader(value:String) {require(value.all{it=='\t' || it in ' '..'~'}){"资源访问令牌或设备标识格式无效，请重新输入；不要包含换行或非 ASCII 字符"}}
 init {if(password==null)validateHeader(token);validateHeader(clientId)}
 private var sessionToken:String?=if(password==null)token else null
 private fun login() {
  require(username.isNotBlank()){ "密码登录需要填写用户名" }
  val body=JsonObject().apply{addProperty("username",username);addProperty("password",password)}
  val response=transport.execute(HttpRequest("POST",URI("$endpoint/api/auth/login"),mapOf("Content-Type" to "application/json","Client-Id" to clientId),body.toString().toByteArray()))
  if(response.status in setOf(400,401,403))throw SourceException(SourceFailure.AUTHENTICATION,response.status)
  val json=jsonResponse(response);val code=json.long("code")?.toInt()?:throw SourceException(SourceFailure.INVALID_RESPONSE)
  if(code!=200)throw SourceException(when(code){400,401,403->SourceFailure.AUTHENTICATION;429->SourceFailure.RATE_LIMITED;else->SourceFailure.NETWORK},code)
  val issued=json.getAsJsonObject("data")?.string("token")?.takeIf{it.isNotBlank()}?:throw SourceException(SourceFailure.AUTHENTICATION,code)
  validateHeader(issued);sessionToken=issued
 }
 private fun requestApi(method:String,path:String,page:Int?):JsonObject {
  val body=JsonObject().apply{addProperty("path",path);addProperty("password",directoryPassword);if(page!=null){addProperty("page",page);addProperty("per_page",200);addProperty("refresh",false)}}
  val response=transport.execute(HttpRequest("POST",URI("$endpoint/api/fs/$method"),mapOf("Authorization" to sessionToken.orEmpty(),"Content-Type" to "application/json","Client-Id" to clientId),body.toString().toByteArray()))
  val json=jsonResponse(response);val code=json.long("code")?.toInt()?:throw SourceException(SourceFailure.INVALID_RESPONSE)
  if(code!=200)throw SourceException(when(code){401,403->SourceFailure.AUTHENTICATION;404->SourceFailure.NOT_FOUND;429->SourceFailure.RATE_LIMITED;else->SourceFailure.NETWORK},code)
  return json.getAsJsonObject("data")?:throw SourceException(SourceFailure.INVALID_RESPONSE)
 }
 @Synchronized private fun api(method:String,path:String,page:Int?=null):JsonObject {
  if(password!=null && sessionToken==null)login()
  return try{requestApi(method,path,page)}catch(error:SourceException){
   if(password!=null && error.status==401){sessionToken=null;login();requestApi(method,path,page)}else throw error
  }
 }
 override fun testConnection():Boolean {listFiles("/");return true}
 override fun listFiles(path:String,page:String?):DirectoryPage {
  val n=page?.toIntOrNull()?:1;require(n>0);val data=api("list",path,n)
  val all=data.getAsJsonArray("content")?.map{it.asJsonObject}.orEmpty()
  val files=all.map{f->SourceFile(pathJoin(path,f.string("name")?:throw SourceException(SourceFailure.INVALID_RESPONSE)),f.string("id"),f.long("size")?:0,f.string("modified")?.let{runCatching{Instant.parse(it).toEpochMilli()}.getOrNull()},f.boolean("is_dir"))}
  val hasMore=if(data.has("has_more"))data.boolean("has_more") else if(data.long("total")!=null)n.toLong()*200<data.long("total")!! else all.size==200
  return DirectoryPage(files,if(hasMore)(n+1).toString() else null)
 }
 override fun listDirectories(path:String,page:String?):DirectoryPage = listFiles(path,page).let{it.copy(files=it.files.filter{f->f.directory})}
 override fun stat(path:String):SourceFile {val f=api("get",path);return SourceFile(path,f.string("id"),f.long("size")?:0,directory=f.boolean("is_dir"))}
 override fun resolvePlayback(path:String):PlaybackLocation {val data=api("get",path);val raw=data.string("raw_url")?:throw SourceException(SourceFailure.INVALID_RESPONSE);val uri=URI(raw);require(uri.scheme in setOf("http","https"));return PlaybackLocation(uri)}
 override fun openRange(path:String,offset:Long,length:Int):ByteArray = exactRange(transport,resolvePlayback(path),offset,length)
}
class WebDavAdapter(endpoint:String,username:String="",password:String="",private val transport:HttpTransport=OkHttpTransport()):SourceAdapter {
 private val endpoint=safeEndpoint(endpoint);private val basePath=this.endpoint.path.trimEnd('/')
 private val headers=if(username.isEmpty() && password.isEmpty())emptyMap() else mapOf("Authorization" to "Basic "+Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8)))
 private fun uri(path:String):URI {require(path.split('/').none{it==".."});return URI(endpoint.scheme,null,endpoint.host,endpoint.port,basePath+"/"+path.trimStart('/'),null,null)}
 private fun properties(path:String,depth:String):List<SourceFile> {
  val requestBody="""<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>""".toByteArray()
  val response=transport.execute(HttpRequest("PROPFIND",uri(path),headers+mapOf("Depth" to depth,"Content-Type" to "application/xml"),requestBody));checkResponse(response)
  val xml=response.body.toString(Charsets.UTF_8);if(xml.contains("<!DOCTYPE",true)||xml.contains("<!ENTITY",true))throw SourceException(SourceFailure.INVALID_RESPONSE)
  val doc=try{parseLocalXml(xml,true)}catch(e:Exception){throw SourceException(SourceFailure.INVALID_RESPONSE)}
  val nodes=doc.getElementsByTagNameNS("DAV:","response");val result=mutableListOf<SourceFile>()
  for(i in 0 until nodes.length){val node=nodes.item(i) as Element;val href=node.getElementsByTagNameNS("DAV:","href").item(0)?.textContent?:continue
   val resolved=uri(path).resolve(href);val p=resolved.path.trimEnd('/');if(p!=basePath && !p.startsWith("$basePath/"))continue
   val groups=node.getElementsByTagNameNS("DAV:","propstat")
   for(j in 0 until groups.length){val group=groups.item(j) as Element;val status=group.getElementsByTagNameNS("DAV:","status").item(0)?.textContent.orEmpty();if(!status.contains(" 200 "))continue
    val collection=group.getElementsByTagNameNS("DAV:","collection").length>0;val size=group.getElementsByTagNameNS("DAV:","getcontentlength").item(0)?.textContent?.trim()?.toLongOrNull()?:0
    result+=SourceFile(p.removePrefix(basePath).ifEmpty{"/"},null,size,directory=collection);break
   }
  };return result
 }
 override fun testConnection():Boolean {properties("/","0");return true}
 override fun listFiles(path:String,page:String?):DirectoryPage {require(page==null);return DirectoryPage(properties(path,"1").filter{it.path.trimEnd('/')!=path.trimEnd('/')})}
 override fun listDirectories(path:String,page:String?):DirectoryPage = listFiles(path,page).let{it.copy(files=it.files.filter{f->f.directory})}
 override fun stat(path:String):SourceFile = properties(path,"0").firstOrNull{it.path.trimEnd('/')==path.trimEnd('/')}?:throw SourceException(SourceFailure.NOT_FOUND)
 override fun resolvePlayback(path:String):PlaybackLocation = PlaybackLocation(uri(path),headers)
 override fun openRange(path:String,offset:Long,length:Int):ByteArray = exactRange(transport,resolvePlayback(path),offset,length)
}
