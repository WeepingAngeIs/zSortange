package dev.anya.storage.pressure;
import java.util.*;
/** Owner-thread FIFO admission intent only. Never owns a save, chunk, lease or inventory. */
public final class AdmissionWindow {
 public record Signal(int queued,int cache,long oldest,long used,long max,boolean failed){}
 private record Wait(UUID pocket,long first,long expiry){}
 private final Thread thread=Thread.currentThread();
 private final LinkedHashMap<UUID,Wait> waiting=new LinkedHashMap<>();
 private boolean blocked;
 private long lastGrant=Long.MIN_VALUE;
 public void update(long now,Signal s){
  own();waiting.values().removeIf(w->now>=w.expiry());
  boolean high=s.failed()||s.queued()>=2048||s.cache()>=12000||s.oldest()>=200||s.used()>s.max()*.80;
  boolean low=!s.failed()&&s.queued()<=512&&s.cache()<=9500&&s.oldest()<=80&&s.used()<s.max()*.65;
  if(high)blocked=true;else if(low)blocked=false;
 }
 public boolean admit(UUID player,UUID pocket,boolean alreadyActive,long now){
  own();if(alreadyActive){waiting.remove(player);return true;}
  Wait old=waiting.get(player);
  // No artificial throttle when there is no backlog or earlier waiting claimant.
  // Queued admissions still drain fairly at one distinct pocket per tick.
  if(old==null&&waiting.isEmpty()&&!blocked){lastGrant=now;return true;}
  if(old==null&&waiting.size()>=32)return false;
  if(old==null||!old.pocket().equals(pocket)){waiting.remove(player);waiting.put(player,new Wait(pocket,now,now+200));}
  else waiting.put(player,new Wait(pocket,old.first(),now+200));
  if(blocked||lastGrant==now||!waiting.keySet().iterator().next().equals(player))return false;
  waiting.remove(player);lastGrant=now;return true;
 }
 public void forget(UUID player){own();waiting.remove(player);}
 public boolean blocked(){own();return blocked;}
 public int waiters(){own();return waiting.size();}
 private void own(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Admission off server thread");}
}
