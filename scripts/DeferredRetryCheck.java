import dev.anya.storage.durable.unload.DeferredRetries;
import java.util.*;
import java.util.concurrent.*;
public final class DeferredRetryCheck {
 static int assertions;
 static void check(boolean ok){assertions++;if(!ok)throw new AssertionError("case "+assertions);}
 static final class Holder {int refs=1;boolean done=true,failed;}
 public static void main(String[] args)throws Exception{
  for(int cap=1;cap<=32;cap++){
   var queue=new DeferredRetries<Integer,Holder>(cap);var owner=new HashMap<Integer,Holder>();var calls=new ArrayList<Holder>();
   for(int i=0;i<cap;i++){var h=new Holder();owner.put(i,h);check(queue.defer(i,h));check(queue.defer(i,h));}
   check(!queue.defer(cap,new Holder()));check(queue.size()==cap);
   for(int t=0;t<20;t++){queue.drain(7,(k,h)->owner.get(k)==h,h->h.refs>0&&h.done&&!h.failed,(k,h)->calls.add(h));check(queue.size()==cap&&calls.isEmpty());}
   // Pending identity resurrection/replacement is retired, never saved by stale callback.
   var stale=owner.get(0);owner.put(0,new Holder());queue.drain(cap,(k,h)->owner.get(k)==h,h->h.refs>0&&h.done&&!h.failed,(k,h)->calls.add(h));check(queue.size()==cap-1&&calls.isEmpty());
   for(var h:owner.values())h.refs=0;
   queue.drain(cap,(k,h)->owner.get(k)==h,h->h.refs>0&&h.done&&!h.failed,(k,h)->calls.add(h));check(queue.size()==0&&calls.size()==cap-1&&!calls.contains(stale));
   // Future changed to incomplete: rearm vanilla waiting, not an unconditional save.
   var h=new Holder();h.done=false;owner.put(0,h);check(queue.defer(0,h));queue.drain(1,(k,v)->owner.get(k)==v,v->v.refs>0&&v.done&&!v.failed,(k,v)->check(!v.done));check(queue.size()==0);
   h.done=true;h.refs=0;check(queue.defer(0,h));try{queue.drain(1,(k,v)->true,v->false,(k,v)->{throw new IllegalStateException();});throw new AssertionError();}catch(IllegalStateException expected){}check(queue.size()==1);
   var error=new java.util.concurrent.atomic.AtomicBoolean();Thread thread=new Thread(()->{try{queue.defer(1,h);}catch(IllegalStateException expected){error.set(true);}});thread.start();thread.join();check(error.get());
  }
  System.out.println("ANYA_DEFERRED_RETRY_CORE_OK assertions="+assertions+"; owner-thread/cap/fair rotation/stale identity/wait/rearm/exception");
 }
}
