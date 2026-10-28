package cn.childtv;
public class AdminSecurityCheck {
 public static void main(String[] args) {
  long[] time={1000}; AdminSecurity s=new AdminSecurity(()->time[0]);
  String hash=s.hashPassword("correct horse battery");
  check(!hash.contains("correct horse"),"password verifier must hide plaintext");
  check(s.login("wrong",hash)==null,"wrong password denied");
  AdminSecurity.Session login=s.login("correct horse battery",hash);
  check(login!=null,"valid password accepted");
  check(s.authorize(login.token,login.csrfToken,"http://192.168.1.2:8080","192.168.1.2:8080",true),"same origin mutation accepted");
  check(!s.authorize(login.token,"bad","http://192.168.1.2:8080","192.168.1.2:8080",true),"CSRF denied");
  check(!s.authorize(login.token,login.csrfToken,"http://evil.test","192.168.1.2:8080",true),"origin denied");
  time[0]+=3600001; check(s.session(login.token)==null,"session expiry");
  AdminSecurity limited=new AdminSecurity(()->time[0]);
  repeatWrong(limited,hash); check(limited.login("correct horse battery",hash)==null,"cooldown enforced");
  time[0]+=30001; check(limited.login("correct horse battery",hash)!=null,"cooldown expires");
  System.out.println("PASS AdminSecurityCheck (8 checks)");
 }
 static void repeatWrong(AdminSecurity s,String hash){for(int i=0;i<3;i++)s.login("bad",hash);}
 static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
