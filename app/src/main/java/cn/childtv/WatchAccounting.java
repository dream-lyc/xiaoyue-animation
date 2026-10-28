package cn.childtv;
public final class WatchAccounting {
 public static Plan split(long watchedSeconds,long remainderMillis,long totalSeconds,long durationMillis){if(watchedSeconds<0||remainderMillis<0||remainderMillis>=1000||totalSeconds<0||durationMillis<0)throw new IllegalArgumentException("Invalid watch duration");long remaining=Math.max(0,Math.subtractExact(Math.multiplyExact(Math.max(0,totalSeconds-watchedSeconds),1000),remainderMillis));long normal=Math.min(remaining,durationMillis);long charged=Math.addExact(durationMillis,remainderMillis);return new Plan(normal,durationMillis-normal,charged/1000,charged%1000);}
 public static final class Plan {public final long normalMillis,graceMillis,chargedSeconds,remainderMillis;Plan(long n,long g,long c,long r){normalMillis=n;graceMillis=g;chargedSeconds=c;remainderMillis=r;}}
}
