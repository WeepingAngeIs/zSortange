package dev.anya.storage.pocket;

import dev.anya.storage.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.*;
import java.util.*;

/** Explicit CI restart fixture, not a connected player reconnect test. */
public final class PocketPersistenceSmoke {
    private static final BlockPos HOME=new BlockPos(-32,80,-32),ENTRANCE=new BlockPos(-30,80,-32);
    private static final UUID RETURNER=UUID.fromString("f3e862e7-878a-4b93-98b1-4e65c4809301");
    public static void run(MinecraftServer server,String phase)throws Exception{
        if(!System.getProperty("anyastorage.guidePersistence","").equals(phase))throw new IllegalStateException("CI-only fixture");
        var home=server.overworld();home.getChunkAt(HOME);home.getChunkAt(ENTRANCE);StorageEntity hub=(StorageEntity)home.getBlockEntity(HOME);
        var ledger=PocketService.ledger(server);var level=PocketService.dimension(server);check(level!=null,"Pocket dimension registered");
        PocketLedger.Pocket record;
        if(phase.equals("seed")){
            check(home.isEmptyBlock(ENTRANCE),"Fresh fixture required");home.setBlockAndUpdate(ENTRANCE,PocketContent.ENTRANCE.get().defaultBlockState());var entrance=(PocketContent.EntranceEntity)home.getBlockEntity(ENTRANCE);entrance.owner=hub.owner;
            record=ledger.reserve(hub.owner,new PocketLedger.Anchor(home.dimension().location().toString(),HOME.asLong(),hub.storageIdentity(),ENTRANCE.asLong()),"beach",64);
            entrance.pocket=record.id();entrance.setChanged();
            ledger.stage(record.id(),PocketLedger.Stage.RESERVED,PocketLedger.Stage.BUILDING);
            for(BlockPos pos:PocketService.chestPositions(record)){level.getChunkAt(pos);level.setBlockAndUpdate(pos,Content.CRATE.get().defaultBlockState());var chest=(StorageEntity)level.getBlockEntity(pos);chest.owner=hub.owner;chest.pocketId=record.id();ItemStack item=new ItemStack(Items.EMERALD,23);item.set(DataComponents.CUSTOM_NAME,Component.literal("Pocket restart conserved"));chest.setItem(2,item);chest.setChanged();}
            level.getChunkSource().save(true);ledger.stage(record.id(),PocketLedger.Stage.BUILDING,PocketLedger.Stage.READY);
            ledger.reserveReturn(new PocketLedger.ReturnPoint(RETURNER,record.id(),"minecraft:overworld",-29.5,81,-31.5,90,0));
        }else{
            record=ledger.pockets().stream().filter(p->p.anchor().entrance()==ENTRANCE.asLong()).findFirst().orElseThrow();
            check(record.stage()==PocketLedger.Stage.READY&&record.anchor().hubId().equals(hub.storageIdentity()),"Persistent pocket and home identity survived");
            check(home.getBlockEntity(ENTRANCE) instanceof PocketContent.EntranceEntity entrance&&record.id().equals(entrance.pocket),"Entrance identity survived");
            for(BlockPos pos:PocketService.chestPositions(record)){level.getChunkAt(pos);check(level.getBlockEntity(pos) instanceof StorageEntity,"Physical chest survived");var chest=(StorageEntity)level.getBlockEntity(pos);check(record.id().equals(chest.pocketId)&&chest.getItem(2).getCount()==23&&chest.getItem(2).getHoverName().getString().equals("Pocket restart conserved"),"Physical inventory components survived");}
            check(ledger.returning(RETURNER)!=null,"Unfinished return record survived");
        }
        check(PocketService.ownedTicketRequests()==0,"Runtime leases were not persisted or recreated on restart");
        AnyaStorage.LOG.info("ANYA_POCKET_RESTART_{}: exact identity, four real chunk inventories and durable unfinished-return record; no runtime tickets restored",phase.toUpperCase(Locale.ROOT));
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private PocketPersistenceSmoke(){}
}
