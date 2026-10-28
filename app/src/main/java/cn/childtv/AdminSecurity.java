package cn.childtv;

import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Process-local admin sessions. Password verifier is the only persisted value. */
public final class AdminSecurity {
 private final LongSupplier clock;
 private final SecureRandom random=new SecureRandom();
 private final Map<String,Session> sessions=new HashMap<>();
 private int failures;
 private long blockedUntil;
 public AdminSecurity(LongSupplier clock) { this.clock=clock; }
 public String hashPassword(String password) {
  if(password.length()<12)throw new IllegalArgumentException("管理员密码至少 12 个字符");
  byte[] salt=new byte[16];random.nextBytes(salt);
  return Base64.getEncoder().encodeToString(salt)+":"+derive(password,salt);
 }
 private String derive(String password,byte[] salt) {
  PBEKeySpec key=new PBEKeySpec(password.toCharArray(),salt,120000,256);
  try{return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(key).getEncoded());}
  catch(Exception e){throw new IllegalStateException("Password verifier unavailable",e);}
  finally {key.clearPassword();}
 }
 public synchronized Session login(String password,String verifier) {
  if(clock.getAsLong()<blockedUntil)return null;
  boolean valid=false;
  try {String[] parts=verifier.split(":");valid=parts.length==2&&MessageDigest.isEqual(derive(password,Base64.getDecoder().decode(parts[0])).getBytes(java.nio.charset.StandardCharsets.UTF_8),parts[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(IllegalArgumentException ignored){}
  if(!valid){if(++failures>=3){blockedUntil=clock.getAsLong()+30000;failures=0;}return null;}
  failures=0;Session result=new Session(token(),token(),clock.getAsLong()+3600000);sessions.put(result.token,result);return result;
 }
 private String token(){byte[] b=new byte[32];random.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
 public synchronized Session session(String token){Session s=sessions.get(token);if(s!=null&&s.expiresAt<=clock.getAsLong()){sessions.remove(token);return null;}return s;}
 public synchronized boolean authorize(String token,String csrf,String origin,String host,boolean mutation) {
  Session s=session(token);if(s==null||host==null)return false;
  try {URI uri=URI.create("http://"+host);String hostname=uri.getHost();if(hostname==null||(!hostname.matches("[0-9.]+")&&!hostname.contains(":")))return false;
   if(origin!=null&&!origin.equals("http://"+host))return false;
  }catch(IllegalArgumentException e){return false;}
  return !mutation||(s.csrfToken.equals(csrf)&&("http://"+host).equals(origin));
 }
 public synchronized void revokeAll(){sessions.clear();}
 public synchronized void logout(String token){sessions.remove(token);}
 public static final class Session {
  public final String token,csrfToken;public final long expiresAt;
  Session(String token,String csrfToken,long expiresAt){this.token=token;this.csrfToken=csrfToken;this.expiresAt=expiresAt;}
 }
}
