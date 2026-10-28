package cn.childtv;
public final class WatchAccountingCheck {
 public static void main(String[] args){
  long seconds=0,remainder=0;for(int i=0;i<10;i++){WatchAccounting.Plan p=WatchAccounting.split(seconds,remainder,45*60,150);seconds+=p.chargedSeconds;remainder=p.remainderMillis;}
  if(seconds!=1||remainder!=500)throw new AssertionError("Fractional pauses must total 1500ms, found "+seconds+"s "+remainder+"ms");
  WatchAccounting.Plan crossing=WatchAccounting.split(59,500,60,5000);
  if(crossing.normalMillis!=500||crossing.graceMillis!=4500)throw new AssertionError("Threshold must split 500ms normal /4500ms grace");
  if(crossing.chargedSeconds!=5||crossing.remainderMillis!=500)throw new AssertionError("Remainder must survive threshold");
  System.out.println("PASS WatchAccountingCheck (3 checks)");
 }
}
