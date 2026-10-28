package cn.childtv;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
public final class BackupIntegrity {
 private BackupIntegrity(){}
 public static String digest(String payload){
  try {byte[] bytes=MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();}
  catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
 public static boolean valid(String payload,String expected){return expected!=null&&expected.matches("[0-9a-f]{64}")&&MessageDigest.isEqual(digest(payload).getBytes(StandardCharsets.US_ASCII),expected.getBytes(StandardCharsets.US_ASCII));}
}
