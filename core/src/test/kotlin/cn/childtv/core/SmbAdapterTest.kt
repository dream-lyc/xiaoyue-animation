package cn.childtv.core
import kotlin.test.*
class SmbAdapterTest {
 @Test fun readsRandomRangesAndClosesLiveHandleWithoutExposingCredentials() {
  val requests=mutableListOf<String>();var closed=0
  val factory=SmbConnectionFactory { host,port,share,user,password,domain ->
   assertEquals("nas",host);assertEquals(445,port);assertEquals("media",share)
   object:SmbConnection {
    override fun list(path:String):List<SourceFile>{requests+=path;return listOf(SourceFile("Peppa.mp4",size=200000))}
    override fun stat(path:String)=SourceFile(path,size=200000)
    override fun open(path:String):RandomAccessReader {requests+=path;return object:RandomAccessReader {override val size=200000L;override fun read(offset:Long,buffer:ByteArray,bufferOffset:Int,length:Int):Int {repeat(length){buffer[bufferOffset+it]=(offset+it).toByte()};return length};override fun close(){closed++}}}
    override fun close(){closed++}
   }
  }
  val a=SmbAdapter("smb://nas/media/cartoon","user","secret",factory=factory)
  assertEquals("/Peppa.mp4",a.listFiles("/").files.single().path)
  assertContentEquals(byteArrayOf(10,11,12,13),a.openRange("/Peppa.mp4",10,4));assertTrue(requests.contains("cartoon\\Peppa.mp4"));assertEquals(3,closed)
  assertFalse(a.resolvePlayback("/Peppa.mp4").uri.toString().contains("secret"));assertFailsWith<IllegalArgumentException>{a.openRead("/../escape")}
 }
}
