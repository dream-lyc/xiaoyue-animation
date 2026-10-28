package cn.childtv.core

import java.net.URI

enum class SourceType { OPENLIST,ALIST,SMB,WEBDAV }
data class Source(val id:String=newId(),val name:String,val type:SourceType,val endpoint:String,val credentialRef:String?=null,val roots:List<String> = emptyList())
data class SourceRoot(val id:String=newId(),val sourceId:String,val path:String)
data class SourceFile(val path:String,val stableId:String?=null,val size:Long,val modifiedAt:Long?=null,val directory:Boolean=false)
data class MediaAsset(val id:String=newId(),val sourceId:String,val path:String,val stableId:String?,val size:Long,val available:Boolean=true,val movieHash:String?=null)
data class DirectoryPage(val files:List<SourceFile>,val nextPage:String?=null)
data class PlaybackLocation(val uri:URI,val headers:Map<String,String> = emptyMap(),val expiresAtMillis:Long?=null)
interface SourceAdapter {
 fun testConnection():Boolean
 fun listDirectories(path:String,page:String?=null):DirectoryPage
 fun listFiles(path:String,page:String?=null):DirectoryPage
 fun stat(path:String):SourceFile
 fun openRange(path:String,offset:Long,length:Int):ByteArray
 fun resolvePlayback(path:String):PlaybackLocation
}
enum class ScanPhase { DISCOVERING,MATCHING,ENRICHING,COMPLETED,CANCELLED,RETRY }
data class ScanJob(val id:String=newId(),val sourceId:String,val roots:List<String>,val phase:ScanPhase=ScanPhase.DISCOVERING,val discovered:Int=0,val matched:Int=0,val cursor:String?=null,val error:String?=null)
class AssetCatalog(initial:Collection<MediaAsset> = emptyList()) {
 private val storage=initial.associateBy{it.id}.toMutableMap()
 val assets:Map<String,MediaAsset> get()=synchronized(this){storage.toMap()}
 @Synchronized fun reconcile(sourceId:String,root:String,files:List<SourceFile>,complete:Boolean):List<MediaAsset> {
  val normalizedRoot=normalize(root);val next=storage.toMutableMap();val seen=mutableSetOf<String>()
  val result=files.filter{!it.directory}.map{f ->
   val path=normalize(f.path);require(underRoot(path,normalizedRoot));require(f.size>=0)
   val old=next.values.find{it.sourceId==sourceId && (if(f.stableId!=null)it.stableId==f.stableId else it.path==path)}
   val asset=MediaAsset(old?.id?:newId(),sourceId,path,f.stableId,f.size,true,old?.movieHash)
   next[asset.id]=asset;seen+=asset.id;asset
  }
  if(complete)next.values.toList().filter{it.sourceId==sourceId && underRoot(it.path,normalizedRoot) && it.id !in seen}.forEach{next[it.id]=it.copy(available=false)}
  storage.clear();storage.putAll(next);return result
 }
 private fun underRoot(path:String,root:String)=root=="/" || path==root || path.startsWith("$root/")
 private fun normalize(path:String):String {val parts=path.replace('\\','/').split('/').filter{it.isNotEmpty() && it!="."};require(parts.none{it==".."});return "/"+parts.joinToString("/")}
}
