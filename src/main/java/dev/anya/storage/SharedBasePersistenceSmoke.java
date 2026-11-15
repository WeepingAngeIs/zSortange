package dev.anya.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.*;
import java.util.UUID;

/** Called only by the opt-in dedicated-server CI probe against a newly created test world. */
public final class SharedBasePersistenceSmoke {
    private static final UUID OWNER=UUID.fromString("b6fbc44e-719a-49fb-a532-b5b1111f18bf");
    private static final UUID MEMBER=UUID.fromString("4986b67f-626a-4889-beb3-ad4784916d67");
    private static final BlockPos POS=new BlockPos(-32,80,-32);
    public static void run(MinecraftServer server,String phase){
        if(!System.getProperty("anyastorage.guidePersistence","").equals(phase))throw new IllegalStateException("CI-only probe");
        var policy=ExpansionRules.get();policy.activityLogging=true;policy.activityActorIds=false;
        var level=server.overworld();level.getChunkAt(POS);
        if(phase.equals("seed")){
            check(level.getBlockState(POS).isAir(),"Test location must be fresh");level.setBlockAndUpdate(POS,Content.HUB.get().defaultBlockState());
            StorageEntity hub=(StorageEntity)level.getBlockEntity(POS);hub.owner=OWNER;hub.baseName="Restart Policy Test";hub.setMember(MEMBER,BasePermissions.VIEW);hub.oreProgress=57;
            hub.setItem(0,new ItemStack(Items.EMERALD,7));hub.activity.record(policy,1000,MEMBER,BaseActivity.Action.DEPOSIT,7);hub.setChanged();
        }else if(phase.equals("verify")){
            check(level.getBlockEntity(POS) instanceof StorageEntity,"Hub missing after restart");StorageEntity hub=(StorageEntity)level.getBlockEntity(POS);
            check(OWNER.equals(hub.owner)&&hub.memberMask(MEMBER)==BasePermissions.VIEW,"Owner/rights changed on restart");
            check(hub.oreProgress==57&&hub.getItem(0).getCount()==7&&hub.baseName.equals("Restart Policy Test"),"Existing hub data changed");
            var history=hub.activity.visible(policy);check(history.size()==1&&history.get(0).count()==7&&history.get(0).actor()==null,"Audit history did not survive restart");
        }else throw new IllegalArgumentException("Unknown phase");
        AnyaStorage.LOG.info("ANYA_SHARED_BASE_PERSISTENCE_{}: actual hub rights, existing inventory and opt-in log survived",phase.toUpperCase(java.util.Locale.ROOT));
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private SharedBasePersistenceSmoke(){}
}
