package cn.childtv.core

import java.net.URI
import java.io.Closeable
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException

interface RandomAccessReader:Closeable {val size:Long;fun read(offset:Long,buffer:ByteArray,bufferOffset:Int,length:Int):Int}
interface SmbConnection:Closeable {fun list(path:String):List<SourceFile>;fun stat(path:String):SourceFile;fun open(path:String):RandomAccessReader}
fun interface SmbConnectionFactory {fun connect(host:String,port:Int,share:String,username:String,password:String,domain:String):SmbConnection}
class SmbAdapter(endpoint:String,private val username:String="",private val password:String="",private val domain:String="",private val factory:SmbConnectionFactory=SmbjConnectionFactory()):SourceAdapter {
 private val endpoint=URI(endpoint).also{require(it.scheme=="smb" && it.host!=null && it.userInfo==null && it.query==null && it.fragment==null)}
 private val segments=this.endpoint.path.split('/').filter{it.isNotEmpty()}.also{require(it.isNotEmpty() && it.none{p->p==".."})}
 private val share=segments.first();private val base=segments.drop(1)
 private fun path(path:String):String {val parts=path.replace('\\','/').split('/').filter{it.isNotEmpty()};require(parts.none{it==".." || it=="."});return (base+parts).joinToString("\\")}
 private fun connect():SmbConnection = factory.connect(endpoint.host,if(endpoint.port<0)445 else endpoint.port,share,username,password,domain)
 override fun testConnection():Boolean {connect().use{it.list(path("/"))};return true}
 override fun listFiles(path:String,page:String?):DirectoryPage {require(page==null);return connect().use{c->DirectoryPage(c.list(path(path)).filter{it.path !in setOf(".","..")}.map{it.copy(path=pathJoin(path,it.path.substringAfterLast('\\').substringAfterLast('/')))})}}
 override fun listDirectories(path:String,page:String?):DirectoryPage = listFiles(path,page).let{it.copy(files=it.files.filter{f->f.directory})}
 override fun stat(path:String):SourceFile = connect().use{it.stat(path(path)).copy(path=path)}
 fun openRead(path:String):RandomAccessReader {
  val relative=path(path);val connection=connect()
  val file=try{connection.open(relative)}catch(e:Exception){connection.close();throw e}
  return object:RandomAccessReader {override val size=file.size;override fun read(offset:Long,buffer:ByteArray,bufferOffset:Int,length:Int):Int {require(offset>=0 && bufferOffset>=0 && length>=0 && bufferOffset<=buffer.size-length);return file.read(offset,buffer,bufferOffset,length)};override fun close(){try{file.close()}finally{connection.close()}}}
 }
 override fun openRange(path:String,offset:Long,length:Int):ByteArray {require(offset>=0 && length in 1..1_048_576);return openRead(path).use{reader->val bytes=ByteArray(length);var done=0;while(done<length){val n=reader.read(offset+done,bytes,done,length-done);if(n<=0)throw SourceException(SourceFailure.RANGE_UNSUPPORTED);done+=n};bytes}}
 override fun resolvePlayback(path:String):PlaybackLocation {val relative=path(path).replace('\\','/');return PlaybackLocation(URI("smb",null,endpoint.host,endpoint.port,"/$share/$relative",null,null))}
}
class SmbjConnectionFactory:SmbConnectionFactory {
 override fun connect(host:String,port:Int,share:String,username:String,password:String,domain:String):SmbConnection {
  val client=SMBClient(SmbConfig.builder().withTimeout(30,TimeUnit.SECONDS).withSoTimeout(30,TimeUnit.SECONDS).build())
  try {
   val connection=client.connect(host,port)
   try {
    val session=connection.authenticate(AuthenticationContext(username,password.toCharArray(),domain))
    try {
     val disk=session.connectShare(share) as? DiskShare?:throw SourceException(SourceFailure.INVALID_RESPONSE)
     return object:SmbConnection {
      override fun list(path:String):List<SourceFile> = guarded {disk.list(path).filter{it.fileName!="." && it.fileName!=".."}.map{SourceFile(it.fileName,if(it.fileId!=0L)java.lang.Long.toUnsignedString(it.fileId) else null,it.endOfFile,it.lastWriteTime.toEpochMillis(),(it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value)!=0L)}}
      override fun stat(path:String):SourceFile = guarded {val info=disk.getFileInformation(path);SourceFile(path,info.internalInformation.indexNumber.takeIf{it!=0L}?.let{java.lang.Long.toUnsignedString(it)},info.standardInformation.endOfFile,info.basicInformation.lastWriteTime.toEpochMillis(),info.standardInformation.isDirectory)}
      override fun open(path:String):RandomAccessReader = guarded {
       val file=disk.openFile(path,EnumSet.of(AccessMask.FILE_READ_DATA,AccessMask.FILE_READ_ATTRIBUTES),EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),SMB2ShareAccess.ALL,SMB2CreateDisposition.FILE_OPEN,EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))
       val size=try{file.getFileInformation(FileStandardInformation::class.java).endOfFile}catch(e:Exception){file.close();throw e}
       object:RandomAccessReader {override val size=size;override fun read(offset:Long,buffer:ByteArray,bufferOffset:Int,length:Int):Int = guarded{file.read(buffer,offset,bufferOffset,length)};override fun close(){file.close()}}
      }
      override fun close(){try{disk.close()}finally{try{session.close()}finally{try{connection.close()}finally{client.close()}}}}
     }
    }catch(e:Exception){session.close();throw e}
   }catch(e:Exception){connection.close();throw e}
  }catch(e:Exception){client.close();throw sanitizeSmb(e)}
 }
 private fun <T> guarded(block:()->T):T=try{block()}catch(e:Exception){throw sanitizeSmb(e)}
 private fun sanitizeSmb(e:Exception):SourceException {
  if(e is SourceException)return e
  val status=(e as? SMBApiException)?.statusCode
  return SourceException(when(status){0xC0000022L,0xC000006DL,0xC000006AL->SourceFailure.AUTHENTICATION;0xC0000034L,0xC000003AL->SourceFailure.NOT_FOUND;else->SourceFailure.NETWORK})
 }
}
