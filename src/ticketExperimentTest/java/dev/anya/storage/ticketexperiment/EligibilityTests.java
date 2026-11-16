package dev.anya.storage.ticketexperiment;
import dev.anya.storage.pocket.*;
import java.util.*;
public final class EligibilityTests {
    static long assertions;
    static void check(boolean v){assertions++;if(!v)throw new AssertionError("Eligibility differential failed at assertion "+assertions);}
    public static void main(String[] args){
        Random random=new Random(860612);long now=100;
        for(int test=0;test<6000;test++){
            Map<UUID,PocketLedger.Pocket> rooms=new HashMap<>();List<PocketLeases.Lease> leases=new ArrayList<>();Set<UUID> preparing=new HashSet<>();List<EligibilitySnapshot.Occupant> occupants=new ArrayList<>();
            for(int i=0;i<8;i++){
                UUID id=new UUID(0,i),owner=new UUID(1,i);var room=new PocketLedger.Pocket(id,owner,i,new PocketLedger.Anchor("minecraft:overworld",0,owner,1),"galaxy",PocketLedger.Stage.values()[random.nextInt(3)],4);
                if(random.nextInt(10)!=0)rooms.put(id,room);if(random.nextInt(7)==0)preparing.add(id);
                for(int n=0;n<2;n++)if(random.nextBoolean())leases.add(new PocketLeases.Lease(new UUID(n+2,i),id,random.nextInt(3)==0?PocketLeases.Kind.VISIT:PocketLeases.Kind.REMOTE,random.nextInt(9)==0?3:4,random.nextInt(10)==0?99:180,random.nextInt(10)==0?100:900));
                if(random.nextInt(4)==0)occupants.add(new EligibilitySnapshot.Occupant((room.x()>>4)+random.nextInt(6)-2,(room.z()>>4)+random.nextInt(6)-2));
            }
            Set<UUID> pending=new HashSet<>();for(var l:leases)if(random.nextInt(9)==0)pending.add(l.player());
            var decision=EligibilitySnapshot.compute(leases,rooms::get,preparing,pending,occupants,now);Set<UUID> active=new HashSet<>();leases.forEach(l->active.add(l.pocket()));check(decision.active().equals(active));
            for(UUID id:active){var r=rooms.get(id);boolean allowed=r!=null&&r.stage()==PocketLedger.Stage.READY&&!preparing.contains(id);
                for(var l:leases)if(l.pocket().equals(id)&&(!allowed||pending.contains(l.player())||l.kind()!=PocketLeases.Kind.REMOTE||l.epoch()!=r.epoch()||now>=l.expires()||now>=l.deadline()))allowed=false;
                if(r!=null)for(var p:occupants)if(p.chunkX()>=((r.x()-1)>>4)&&p.chunkX()<=((r.x()+32)>>4)&&p.chunkZ()>=((r.z()-1)>>4)&&p.chunkZ()<=((r.z()+32)>>4))allowed=false;
                check(decision.remoteOnly().contains(id)==allowed);
            }
            check(decision.pocketVisits()<=8&&decision.leaseVisits()<=32);check(decision.occupantComparisons()<=active.size()*occupants.size());
        }
        System.out.println("ANYA_ELIGIBILITY_TEST_OK assertions="+assertions+" deterministic differential snapshots=6000; not integration tests");
    }
}
