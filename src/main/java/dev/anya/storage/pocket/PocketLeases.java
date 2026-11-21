package dev.anya.storage.pocket;

import java.util.*;

/** Ephemeral bounded leases. This class never persists tickets or assumes how many chunks Minecraft loads. */
public final class PocketLeases {
    public enum Kind { VISIT, REMOTE }
    public record Lease(UUID player,UUID pocket,Kind kind,long epoch,long expires,long deadline){}
    private final Map<UUID,Lease> leases=new HashMap<>();
    public synchronized boolean acquire(UUID player,UUID pocket,Kind kind,long epoch,long now,int maxPockets,int maxPeople,long ttl,long lifetime){
        if(maxPockets<1||maxPockets>8||maxPeople<1||maxPeople>64||ttl<1||lifetime<ttl)throw new IllegalArgumentException("Invalid lease bounds");
        expire(now);Lease old=leases.get(player);
        if(old!=null&&(!old.pocket().equals(pocket)||old.kind()!=kind))return false;
        if(old==null){
            if(leases.size()>=maxPeople)return false;
            if(!active().contains(pocket)&&active().size()>=maxPockets)return false;
        }
        long deadline=old==null?Math.addExact(now,lifetime):old.deadline();
        if(now>=deadline)return false;
        leases.put(player,new Lease(player,pocket,kind,epoch,Math.min(Math.addExact(now,ttl),deadline),deadline));return true;
    }
    private long visitGeneration;
    /** Pending requests retain their deadline. Only a completed physical arrival lifts it. */
    synchronized void arrived(UUID player){
        Lease l=leases.get(player);
        if(l==null||l.kind()!=Kind.VISIT)throw new IllegalStateException("Missing visit");
        leases.put(player,new Lease(player,l.pocket(),l.kind(),l.epoch(),l.expires(),Long.MAX_VALUE-visitGeneration++));
    }
    public synchronized boolean renew(UUID player,long epoch,long now,long ttl){
        Lease l=leases.get(player);if(l==null||l.epoch()!=epoch||now>=l.expires()||now>=l.deadline()){leases.remove(player);return false;}
        leases.put(player,new Lease(l.player(),l.pocket(),l.kind(),l.epoch(),Math.min(now+ttl,l.deadline()),l.deadline()));return true;
    }
    public synchronized Lease get(UUID player){return leases.get(player);}
    public synchronized Lease release(UUID player){return leases.remove(player);}
    public synchronized List<Lease> expire(long now){var out=new ArrayList<Lease>();leases.values().removeIf(l->{if(now>=l.expires()||now>=l.deadline()){out.add(l);return true;}return false;});return out;}
    public synchronized Set<UUID> active(){var out=new HashSet<UUID>();leases.values().forEach(l->out.add(l.pocket()));return Set.copyOf(out);}
    public synchronized List<Lease> snapshot(){return List.copyOf(leases.values());}
    public synchronized void clear(){leases.clear();}
}
