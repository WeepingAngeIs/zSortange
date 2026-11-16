package dev.anya.storage.ticketexperiment;

import dev.anya.storage.pocket.*;
import java.util.*;
import java.util.function.Function;

/** Pure per-synchronization decision. No saved state, no cached authorization, no ticket mutation. */
public final class EligibilitySnapshot {
    public record Occupant(int chunkX,int chunkZ){}
    public record Decision(Set<UUID> active,Set<UUID> remoteOnly,int leaseVisits,int pocketVisits,int occupantComparisons){}
    public static Decision compute(List<PocketLeases.Lease> leases,Function<UUID,PocketLedger.Pocket> lookup,
                                   Set<UUID> preparing,Set<UUID> pendingPlayers,List<Occupant> occupants,long now){
        Set<UUID> active=new HashSet<>(),blocked=new HashSet<>();
        for(var lease:leases){
            active.add(lease.pocket());
            if(pendingPlayers.contains(lease.player())||lease.kind()!=PocketLeases.Kind.REMOTE||now>=lease.expires()||now>=lease.deadline())blocked.add(lease.pocket());
        }
        Map<UUID,PocketLedger.Pocket> eligible=new HashMap<>();int checks=0;
        for(UUID id:active){
            var room=lookup.apply(id);checks++;
            if(room!=null&&room.stage()==PocketLedger.Stage.READY&&!preparing.contains(id)&&!blocked.contains(id))eligible.put(id,room);
        }
        // A stale epoch must never make a destination weaker before normal lease validation.
        for(var lease:leases){var room=eligible.get(lease.pocket());if(room!=null&&room.epoch()!=lease.epoch())eligible.remove(lease.pocket());}
        int comparisons=0;
        for(Occupant player:occupants){
            for(var it=eligible.values().iterator();it.hasNext();){
                var room=it.next();comparisons++;
                if(player.chunkX()>=((room.x()-1)>>4)&&player.chunkX()<=((room.x()+32)>>4)
                        &&player.chunkZ()>=((room.z()-1)>>4)&&player.chunkZ()<=((room.z()+32)>>4))it.remove();
            }
        }
        return new Decision(Set.copyOf(active),Set.copyOf(eligible.keySet()),leases.size()*2,checks,comparisons);
    }
    private EligibilitySnapshot(){}
}
