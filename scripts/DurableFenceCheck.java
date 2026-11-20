import dev.anya.storage.durable.DurableFence;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class DurableFenceCheck {
 static int checks;static void check(boolean v){checks++;if(!v)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
  for(int n=0;n<100;n++){
   var w=new CompletableFuture<Void>();var f=new CompletableFuture<Void>();var begun=new AtomicInteger();
   var gate=new DurableFence(List.of(w),()->{begun.incrementAndGet();return f;});
   for(int i=0;i<10;i++)check(gate.poll()==DurableFence.State.WRITES);check(begun.get()==0);
   w.complete(null);check(gate.poll()==DurableFence.State.FLUSH);check(begun.get()==1);
   for(int i=0;i<10;i++)check(gate.poll()==DurableFence.State.FLUSH);
   f.complete(null);check(gate.poll()==DurableFence.State.DONE);check(begun.get()==1);
   var failed=new DurableFence(List.of(CompletableFuture.failedFuture(new Exception("write"))),()->{throw new AssertionError("flush after failed write");});
   try{failed.poll();throw new AssertionError();}catch(CompletionException expected){check(failed.state()==DurableFence.State.FAILED);}
   var ff=new DurableFence(List.of(CompletableFuture.completedFuture(null)),()->CompletableFuture.failedFuture(new Exception("force")));
   try{ff.poll();throw new AssertionError();}catch(CompletionException expected){check(ff.state()==DurableFence.State.FAILED);}
   var late=new CompletableFuture<Void>();var cancel=new DurableFence(List.of(late),()->{throw new AssertionError("late publish");});cancel.cancel();late.complete(null);check(cancel.poll()==DurableFence.State.CANCELLED);
   var cross=new AtomicReference<Throwable>();var thread=new Thread(()->{try{gate.poll();}catch(Throwable t){cross.set(t);}});thread.start();thread.join();check(cross.get() instanceof IllegalStateException);
  }
  System.out.println("ANYA_DURABLE_FENCE_CHECKS_OK assertions="+checks+" delayed writes/flush, failures, cancellation, thread ownership");
 }
}
