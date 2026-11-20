package dev.anya.storage;
import dev.anya.storage.pocket.PocketLeases;
import java.util.*;
/** Admission and lifetime tests of the real lease allocator; not a client or engine load test. */
public final class MultiPocketLeaseTests {
    private static int assertions;
    private static void check(boolean b,String s){assertions++;if(!b)throw new AssertionError(s);}
    public static void main(String[] args){
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();List<UUID> people=new ArrayList<>();for(int i=0;i<17;i++)people.add(UUID.randomUUID());
        for(int cap=1;cap<=8;cap++){
            PocketLeases l=new PocketLeases();List<UUID> rooms=new ArrayList<>();
            for(int i=0;i<cap;i++){UUID room=UUID.randomUUID();rooms.add(room);check(l.acquire(people.get(i),room,PocketLeases.Kind.VISIT,0,0,cap,16,80,200),"admit configured pocket");}
            check(!l.acquire(people.get(16),UUID.randomUUID(),PocketLeases.Kind.REMOTE,0,1,cap,16,80,200),"reject excess active pocket");
            check(l.acquire(people.get(15),rooms.get(0),PocketLeases.Kind.REMOTE,0,1,1,16,80,200),"lower cap admits shared existing room");
            l.release(people.get(0));check(l.active().contains(rooms.get(0)),"other viewer preserves room");
            l.release(people.get(15));check(!l.active().contains(rooms.get(0)),"last viewer releases room");
            check(l.acquire(people.get(0),UUID.randomUUID(),PocketLeases.Kind.REMOTE,0,2,1,16,80,200)==(cap==1),"draining limit prevents new rooms");
        }
        PocketLeases l=new PocketLeases();
        for(int i=0;i<16;i++)check(l.acquire(people.get(i),i%2==0?a:b,i%3==0?PocketLeases.Kind.REMOTE:PocketLeases.Kind.VISIT,4,0,2,16,80,200),"viewer admitted");
        check(!l.acquire(people.get(16),a,PocketLeases.Kind.REMOTE,4,1,2,16,80,200),"seventeenth viewer rejected");
        check(!l.acquire(people.get(0),b,PocketLeases.Kind.VISIT,4,1,2,16,80,200),"one identity cannot use two rooms");
        for(int t=1;t<200;t++)for(int i=0;i<16;i++)check(l.renew(people.get(i),4,t,80),"lease renewed only to original deadline");
        check(l.expire(200).size()==16&&l.active().isEmpty(),"absolute deadline drains all rooms");
        for(int cycle=0;cycle<100;cycle++){
            check(l.acquire(people.get(0),a,PocketLeases.Kind.VISIT,cycle,cycle*400L,2,16,80,200),"cycle enter A");
            check(l.acquire(people.get(1),b,PocketLeases.Kind.REMOTE,cycle,cycle*400L,2,16,80,200),"cycle enter B");
            check(!l.renew(people.get(0),cycle+1,cycle*400L+1,80),"stale epoch removed");
            check(l.get(people.get(1))!=null&&l.active().equals(Set.of(b)),"epoch change isolated");l.clear();
        }
        System.out.println("PASS: "+assertions+" NEW multi-pocket lease admission, lower-cap draining, 16-viewer bounds and absolute-lifetime assertions (pure JVM, not multiplayer)");
    }
}
