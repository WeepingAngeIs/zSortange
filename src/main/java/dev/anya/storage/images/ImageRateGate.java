package dev.anya.storage.images;

import java.util.*;

/** Bounded network-thread gate, applied before scheduling any server-thread image work. */
public final class ImageRateGate {
    private record Window(long second,int count) {}
    private final Map<UUID,Window> players=new HashMap<>();
    private long globalSecond=Long.MIN_VALUE;
    private int globalCount;
    public synchronized boolean allow(UUID player,long second) {
        if(second!=globalSecond){globalSecond=second;globalCount=0;players.entrySet().removeIf(e->e.getValue().second()!=second);}
        Window old=players.get(player);int count=old==null?0:old.count();
        if(globalCount>=128||count>=12||(!players.containsKey(player)&&players.size()>=128))return false;
        globalCount++;players.put(player,new Window(second,count+1));return true;
    }
    public synchronized void clear(){players.clear();globalSecond=Long.MIN_VALUE;globalCount=0;}
}
