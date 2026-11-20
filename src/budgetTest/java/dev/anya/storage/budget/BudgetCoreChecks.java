package dev.anya.storage.budget;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
public final class BudgetCoreChecks {
 static int checks;
 static void check(boolean v){checks++;if(!v)throw new AssertionError("Check "+checks);}
 public static void main(String[] args)throws Exception{
  for(int cap:new int[]{1,2,8,32,64}){
   var b=new TickBudget(256,cap,8_000_000);var q=new ArrayDeque<Integer>();for(int i=0;i<1000;i++)q.add(i);
   int done=0;long tick=0;
   while(!q.isEmpty()){
    b.tick(++tick);int old=q.size();while(b.admit()&&!q.isEmpty()){check(q.remove()==done++);b.callback();b.snapshot();b.elapsed(1);}
    check(old-q.size()<=cap);int remaining=q.size();b.tick(tick);if(!q.isEmpty())check(!b.admit()&&q.size()==remaining);
   }check(done==1000);
  }
  var b=new TickBudget(2,100,10);b.tick(1);b.callback();b.callback();check(!b.admit());b.tick(2);check(b.admit());b.elapsed(10);check(!b.admit());
  var failed=new ArrayDeque<Runnable>();failed.add(()->{throw new IllegalStateException("sentinel");});failed.add(()->{});
  b.tick(3);try{failed.remove().run();throw new AssertionError();}catch(IllegalStateException e){check(e.getMessage().equals("sentinel")&&failed.size()==1);}
  b.tick(4);b.elapsed(10);int before=failed.size();if(b.admit())failed.remove().run();check(before==failed.size());
  while(!failed.isEmpty())failed.remove().run();check(failed.isEmpty());
  var off=new AtomicBoolean();var t=new Thread(()->{try{b.admit();}catch(IllegalStateException expected){off.set(true);}});t.start();t.join();check(off.get());
  try{new TickBudget(0,1,1);throw new AssertionError();}catch(IllegalArgumentException expected){check(true);}
  System.out.println("ANYA_BUDGET_CORE_OK assertions="+checks+" queue order, no loss, same-tick accumulation, soft elapsed, bypass, callback error, thread ownership");
 }
}
