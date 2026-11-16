package dev.anya.storage.pressure;
import java.util.*;
public class AdmissionChecks {
 static int n;static void check(boolean v){n++;if(!v)throw new AssertionError("Check "+n);}
 public static void main(String[] args)throws Exception{
  var a=UUID.randomUUID();var b=UUID.randomUUID();var room=UUID.randomUUID();var other=UUID.randomUUID();var w=new AdmissionWindow();
  var high=new AdmissionWindow.Signal(2048,8000,0,100,1000,false);var low=new AdmissionWindow.Signal(0,8000,0,100,1000,false);
  for(int round=0;round<2000;round++){
   long t=round*1000L;w.update(t,high);check(w.blocked());check(!w.admit(a,room,false,t));check(!w.admit(b,other,false,t));
   check(w.admit(UUID.randomUUID(),room,true,t));check(w.waiters()==2);
   w.update(t+1,low);check(!w.admit(b,other,false,t+1));check(w.admit(a,room,false,t+1));check(!w.admit(b,other,false,t+1));check(w.admit(b,other,false,t+2));check(w.waiters()==0);
  }
  var fail=new AdmissionWindow.Signal(0,0,0,0,1000,true);w.update(3_000_000,fail);check(!w.admit(a,room,false,3_000_000));check(w.admit(b,room,true,3_000_000));
  w.update(3_000_300,low);check(w.waiters()==0);check(w.admit(b,other,false,3_000_300));
  w.update(4_000_000,new AdmissionWindow.Signal(0,0,0,900,1000,false));check(w.blocked());w.update(4_000_001,new AdmissionWindow.Signal(0,0,0,700,1000,false));check(w.blocked());w.update(4_000_002,low);check(!w.blocked());
  w.update(5_000_000,high);for(int i=0;i<100;i++)w.admit(UUID.randomUUID(),UUID.randomUUID(),false,5_000_000);check(w.waiters()==32);
  var error=new java.util.concurrent.atomic.AtomicBoolean();var t=new Thread(()->{try{w.waiters();}catch(IllegalStateException ok){error.set(true);}});t.start();t.join();check(error.get());
  System.out.println("ANYA_PRESSURE_CORE_OK assertions="+n+" FIFO, no lease/save ownership, age expiry, capacity, same-active bypass, failure/memory hysteresis, thread ownership");
 }
}
