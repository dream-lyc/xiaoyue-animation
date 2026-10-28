package cn.childtv;
public final class BackupIntegrityCheck {
 public static void main(String[] args){
  String payload="{\"version\":1,\"episodes\":[]}";
  String digest=BackupIntegrity.digest(payload);
  if(digest.length()!=64)throw new AssertionError("SHA-256 manifest must have 64 hexadecimal characters");
  if(!BackupIntegrity.valid(payload,digest))throw new AssertionError("Valid backup rejected");
  if(BackupIntegrity.valid(payload+" ",digest))throw new AssertionError("Tampered payload accepted");
  if(BackupIntegrity.valid(payload,"bad"))throw new AssertionError("Malformed digest accepted");
  System.out.println("PASS BackupIntegrityCheck (4 checks)");
 }
}
