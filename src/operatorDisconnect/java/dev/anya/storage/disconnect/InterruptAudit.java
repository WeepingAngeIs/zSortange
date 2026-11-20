package dev.anya.storage.disconnect;
import dev.anya.storage.durable.Trace;
import dev.anya.storage.repair.RepairService;
import dev.anya.storage.operator.OperatorResolution;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
/** Test observation only: bounded IO-worker pause, selected injected IOException, or lost acknowledgement. */
public final class InterruptAudit {
 public static volatile boolean armed,entered,consumed,finished;
 public static volatile Throwable error;
 public static volatile long key;
 public static volatile String receipt,mode;
 public static final AtomicInteger commands=new AtomicInteger(),faults=new AtomicInteger(),withheld=new AtomicInteger();
 private static volatile CountDownLatch gate=new CountDownLatch(0);
 private static final Field ISSUED;
 static{try{ISSUED=OperatorResolution.class.getDeclaredField("issued");ISSUED.setAccessible(true);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 public static boolean failure(){return mode!=null&&mode.endsWith("-fail");}
 public static void arm(long k,String r,String m){if(armed)throw new IllegalStateException("Rearm would duplicate command");key=k;receipt=r;mode=m;gate=new CountDownLatch(1);armed=true;}
 public static void receiptConsumed(long k,boolean issue){
  if(!armed||issue||k!=key||consumed)return;
  if(!RepairService.server.isSameThread())throw new IllegalStateException("Receipt check must own server thread");
  try{if(((Map<?,?>)ISSUED.get(null)).containsKey(receipt))throw new IllegalStateException("Receipt not actually consumed");}catch(IllegalAccessException e){throw new IllegalStateException(e);}
  consumed=true;Trace.note("COMMAND_RECEIPT_CONSUMED",null,"key="+k+" actor=AnyaQAAlpha;original command parser and resolve path");
 }
 public static void result(long k,CompletableFuture<Void> result){
  if(!armed||k!=key)return;if(commands.incrementAndGet()!=1)throw new IllegalStateException("Confirmation was replayed");
  result.whenComplete((v,x)->{error=x;finished=true;Trace.note("COMMAND_ASYNC_RESULT",null,"mode="+mode+" success="+(x==null)+" error="+x);});
 }
 private static void pause(String point)throws IOException{
  if(entered)return;
  if(RepairService.server.isSameThread())throw new IllegalStateException("Never pause world owner thread");
  entered=true;Trace.marker("INTERRUPT_GATE","mode="+mode+" point="+point+" consumed="+consumed+" worker="+Thread.currentThread().getName());
  try{if(!gate.await(8,TimeUnit.SECONDS))throw new IOException("Eight-second isolated worker-gate deadline");}
  catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
  Trace.note("INTERRUPT_GATE_RELEASED",null,"mode="+mode+" point="+point);
 }
 public static void read(long k)throws IOException{if(armed&&consumed&&mode.equals("receipt-disconnect")&&k==key)pause("receipt-reinspection");}
 public static void publication(String point)throws IOException{
  if(!armed)return;
  String wanted=switch(mode){case "intent-disconnect"->"reset-intent-published";case "projection-disconnect"->"reset-projection-published";case "completion-disconnect"->"reset-published";case "intent-fail"->"reset-intent-before-force";case "projection-fail"->"reset-projection-before-force";case "completion-fail"->"reset-before-force";default->"not-a-journal-cut";};
  if(!point.equals(wanted)||entered)return;
  pause(point);
  if(failure()){faults.incrementAndGet();throw new IOException("Injected operator confirmation "+mode+" before force;retain partial publication");}
 }
 public static boolean acknowledgement(CommandSourceStack source,Supplier<Component> text){
  if(!armed||!mode.equals("ack-disconnect")||!source.getTextName().equals("AnyaQAAlpha"))return false;
  if(!text.get().getString().startsWith("Exact current reset durably completed"))return false;
  if(!RepairService.server.isSameThread()||!finished||error!=null)throw new IllegalStateException("Acknowledgement without committed result");
  if(withheld.incrementAndGet()!=1)throw new IllegalStateException("Duplicate success acknowledgement");
  entered=true;Trace.marker("INTERRUPT_GATE","mode="+mode+" point=acknowledgement;durable operation completed;only this reply is deliberately withheld");return true;
 }
 public static void release(){gate.countDown();}
 private InterruptAudit(){}
}
