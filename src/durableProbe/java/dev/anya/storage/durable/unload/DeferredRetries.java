package dev.anya.storage.durable.unload;

import java.util.*;
import java.util.function.*;

/** Owner-thread retry bookkeeping. It never saves, mutates a holder, or discards an owned wait. */
public final class DeferredRetries<K,H> {
    private final Thread owner=Thread.currentThread();
    private final int limit;
    private final LinkedHashMap<K,H> waiting=new LinkedHashMap<>();
    public long retired,rearmed,replaced,polls;
    public int peak;
    public DeferredRetries(int limit){if(limit<1)throw new IllegalArgumentException();this.limit=limit;}
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Retry bookkeeping used off owner thread");}
    public boolean defer(K key,H holder){
        owner();Objects.requireNonNull(key);Objects.requireNonNull(holder);
        H old=waiting.get(key);if(old==holder)return true;
        if(old==null&&waiting.size()==limit)return false; // Caller retains the vanilla callback, never evicts a save.
        if(old!=null)replaced++;
        waiting.put(key,holder);peak=Math.max(peak,waiting.size());return true;
    }
    /** Exact pending ownership is checked before prerequisites. Retired work no longer owns a save. */
    public void drain(int budget,BiPredicate<K,H> owns,Predicate<H> stillBlocked,BiConsumer<K,H> schedule){
        owner();int n=Math.min(Math.max(0,budget),waiting.size());
        for(int i=0;i<n;i++){
            var iterator=waiting.entrySet().iterator();var first=iterator.next();K key=first.getKey();H holder=first.getValue();iterator.remove();polls++;
            if(!owns.test(key,holder)){retired++;continue;}
            if(stillBlocked.test(holder)){waiting.put(key,holder);continue;}
            // Put back if a synchronous scheduling failure occurs; never silently lose pending responsibility.
            try{schedule.accept(key,holder);rearmed++;}catch(RuntimeException|Error e){waiting.put(key,holder);throw e;}
        }
    }
    public int size(){owner();return waiting.size();}
}
