package dev.anya.storage.budget;
/** Owner-thread accounting only. A false admission never consumes a callback. */
public final class TickBudget {
 private final Thread owner=Thread.currentThread();
 private final int callbacksLimit,snapshotLimit;
 private final long nanosLimit;
 private long tick=Long.MIN_VALUE;
 private int callbacks,snapshots;
 private long nanos;
 public TickBudget(int callbacksLimit,int snapshotLimit,long nanosLimit){
  if(callbacksLimit<1||snapshotLimit<1||nanosLimit<1)throw new IllegalArgumentException("Positive limits required for progress");
  this.callbacksLimit=callbacksLimit;this.snapshotLimit=snapshotLimit;this.nanosLimit=nanosLimit;
 }
 private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Budget accessed off owner thread");}
 public void tick(long value){owner();if(tick!=value){tick=value;callbacks=snapshots=0;nanos=0;}}
 public boolean admit(){owner();return callbacks<callbacksLimit&&snapshots<snapshotLimit&&nanos<nanosLimit;}
 public void callback(){owner();callbacks++;}
 public void snapshot(){owner();snapshots++;}
 public void elapsed(long value){owner();if(value<0)throw new IllegalArgumentException("Negative elapsed");nanos+=value;}
 public int callbacks(){owner();return callbacks;}
 public int snapshots(){owner();return snapshots;}
 public long nanos(){owner();return nanos;}
}
