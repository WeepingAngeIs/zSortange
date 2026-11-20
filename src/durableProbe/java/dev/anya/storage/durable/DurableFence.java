package dev.anya.storage.durable;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
/** Polled by its owner thread. IO callbacks never publish, mutate chunks or touch a ledger. */
public final class DurableFence {
 public enum State { WRITES, FLUSH, DONE, CANCELLED, FAILED }
 private final CompletableFuture<Void> writes;
 private final Supplier<CompletableFuture<Void>> beginFlush;
 private CompletableFuture<Void> flush;
 private State state=State.WRITES;
 private final Thread owner=Thread.currentThread();
 public DurableFence(List<CompletableFuture<Void>> writes,Supplier<CompletableFuture<Void>> beginFlush){
  this.writes=CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));this.beginFlush=beginFlush;
 }
 public State poll(){
  if(Thread.currentThread()!=owner)throw new IllegalStateException("Fence owner thread changed");
  try{
   if(state==State.WRITES&&writes.isDone()){writes.getNow(null);flush=Objects.requireNonNull(beginFlush.get());state=State.FLUSH;}
   if(state==State.FLUSH&&flush.isDone()){flush.getNow(null);state=State.DONE;}
   return state;
  }catch(RuntimeException e){state=State.FAILED;throw e;}
 }
 public void cancel(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Fence owner thread changed");state=State.CANCELLED;}
 public State state(){return state;}
}
