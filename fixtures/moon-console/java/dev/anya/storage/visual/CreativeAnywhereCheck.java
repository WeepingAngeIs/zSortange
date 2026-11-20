package dev.anya.storage.visual;

import dev.anya.storage.*;
import dev.anya.storage.pocket.PocketService;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Integrated runner with a real connected client, including a cold base and a non-op Creative player. */
final class CreativeAnywhereCheck {
    private static final BlockPos HUB=new BlockPos(-2,80,0), CUBE=new BlockPos(0,80,0), ENTRANCE=new BlockPos(-3,80,0);
    private static final InteractionHand HAND=InteractionHand.MAIN_HAND;
    private enum Step { BIND, LEAVE, COLD, REGULAR, DENIED, NETWORK, SEARCH, TAKE, DROP, CLOSED, RESTORE,
        DENY_RIGHTS, COLD_POCKET, POCKET, DOWNGRADE, SURVIVAL, CLEAN, DONE }
    private static Step step=Step.BIND;
    private static int ticks;
    private static ItemStack aggregate, pocketTablet, standard;
    private static UUID pocketId;
    private static int previousShards;
    private static CompletableFuture<Boolean> task;
    private static void require(boolean ok,String why){if(!ok)throw new IllegalStateException("Creative Tablet: "+why);}
    private static void next(Step s){step=s;ticks=0;System.out.println("Creative camera "+s);}
    private static boolean cold(ServerPlayer p,BlockPos pos){
        return p.getServer().overworld().getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)==null;
    }
    private static void bind(ServerPlayer p,BlockPos pos){
        p.setShiftKeyDown(true);
        var hit=new BlockHitResult(Vec3.atCenterOf(pos).relative(Direction.NORTH,.5),Direction.NORTH,pos,false);
        p.gameMode.useItemOn(p,p.serverLevel(),p.getMainHandItem(),HAND,hit);
        p.setShiftKeyDown(false);
    }
    private static void use(ServerPlayer p){
        p.gameMode.useItem(p,p.serverLevel(),p.getMainHandItem(),HAND);
    }
    private static int shards(ServerPlayer p){
        return p.getInventory().countItem(Items.AMETHYST_SHARD);
    }
    private static boolean sessionPending(ServerPlayer p) {
        try {
            var method=CreativeTabletAccess.class.getDeclaredMethod("session",ServerPlayer.class);
            method.setAccessible(true);
            return method.invoke(null,p)!=null;
        } catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static StorageEntity home(ServerPlayer p){return (StorageEntity)p.getServer().overworld().getBlockEntity(HUB);}
    private static boolean tick(ServerPlayer p){
        if(ticks>0 && ticks%200==0)System.out.println("Creative camera waiting "+step+" "+ticks+" ticks, home cold="+cold(p,HUB)
                +", storage cold="+cold(p,CUBE)+", tickets="+CreativeTabletAccess.ticketCount()+", session="+sessionPending(p)
                +", capacity="+(p.containerMenu instanceof StoreMenu m?m.capacity():-1));
        if(++ticks>1800)throw new IllegalStateException("Timed out at Creative Tablet "+step);
        switch(step){
            case BIND -> {
                var level=p.serverLevel();
                level.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0,p.getServer());
                // The reserve of 17 shards is in a different chunk from the hub.
                require((HUB.getX()>>4)!=(CUBE.getX()>>4) && ((StorageEntity)level.getBlockEntity(CUBE)).getItem(215).getCount()==17,"Second storage chunk");
                var privateCrate=(StorageEntity)level.getBlockEntity(CUBE.east(2));
                privateCrate.owner=UUID.randomUUID();privateCrate.setItem(0,new ItemStack(Items.EMERALD,3));
                var legacy=new ItemStack(Content.REMOTE.get());
                var pocketRecord=(dev.anya.storage.pocket.PocketContent.EntranceEntity)level.getBlockEntity(ENTRANCE);
                pocketId=pocketRecord.pocket;
                var old=BagInventory.data(legacy);old.putString("HubDimension",Level.OVERWORLD.location().toString());old.putLong("HubPos",HUB.asLong());old.putUUID("PocketId",pocketId);
                old.putString("FutureExample","keep");BagInventory.data(legacy,old);
                legacy.set(DataComponents.CUSTOM_NAME,Component.literal("My old terminal"));
                p.setItemInHand(InteractionHand.OFF_HAND,legacy.copy());
                p.setItemInHand(HAND,legacy);use(p);
                standard=p.getMainHandItem();
                require(standard.is(Content.CUPID_TABLET.get()) && standard.getHoverName().getString().equals("My old terminal")
                    && BagInventory.data(standard).equals(old),"Old saved heart Remote becomes ordinary tablet with its binding and components");
                // Allow the old item to finish the normal authorized open it requested before changing destinations.
                next(Step.LEAVE);
            }
            case LEAVE -> {
                if(ticks<20)return false;
                var converted=p.getOffhandItem();
                require(converted.is(Content.CUPID_TABLET.get()) && converted.getHoverName().equals(standard.getHoverName())
                    && BagInventory.data(converted).equals(BagInventory.data(standard)),"Passive offhand Remote migration");
                p.closeContainer();
                if(PocketService.lease(p.getUUID())!=null)return false;
                p.setItemInHand(InteractionHand.OFF_HAND,ItemStack.EMPTY);
                p.setGameMode(GameType.CREATIVE);
                p.setItemInHand(HAND,new ItemStack(Content.CREATIVE_TABLET.get()));bind(p,HUB);
                aggregate=p.getMainHandItem();
                require(BagInventory.data(aggregate).contains("HubPos"),"Genuine local Creative hub binding");
                p.setItemInHand(HAND,new ItemStack(Content.CREATIVE_TABLET.get()));bind(p,HUB);bind(p,ENTRANCE);
                pocketTablet=p.getMainHandItem();
                require(pocketId.equals(BagInventory.data(pocketTablet).getUUID("PocketId")),"Genuine local Creative pocket binding");
                p.getInventory().setItem(23,aggregate);p.getInventory().setItem(24,pocketTablet);
                p.setItemInHand(HAND,standard);
                // Use the same real player under level-zero permissions so Creative cannot borrow operator bypass.
                p.getServer().getPlayerList().getOps().add(new ServerOpListEntry(p.getGameProfile(),0,false));
                require(!p.hasPermissions(2),"Non-operator Creative actor");
                p.getServer().saveEverything(false,true,false);
                var away=p.getServer().getLevel(Level.NETHER);
                p.teleportTo(away,320.5,200,320.5,0,0);
                p.getAbilities().flying=true;p.onUpdateAbilities();
                // A safe ledge for the subsequent switch to Survival in the disposable dimension.
                away.setBlockAndUpdate(new BlockPos(320,199,320),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                next(Step.COLD);
            }
            case COLD -> {
                if(!cold(p,HUB) || !cold(p,CUBE))return false;
                require(p.level().dimension()==Level.NETHER,"Physical player left home");
                use(p);next(Step.REGULAR);
            }
            case REGULAR -> {
                if(ticks<10)return false;
                require(p.containerMenu==p.inventoryMenu && cold(p,HUB) && cold(p,CUBE),"Regular tablet must keep its original radius");
                var forged=aggregate.copy();var tag=BagInventory.data(forged);tag.putLong("HubPos",HUB.east(1024).asLong());BagInventory.data(forged,tag);
                p.setItemInHand(HAND,forged);use(p);
                require(!sessionPending(p) && CreativeTabletAccess.ticketCount()==0,"Modified coordinates must be rejected before requesting any chunk");
                next(Step.DENIED);
            }
            case DENIED -> {
                if(ticks<10)return false;
                require(p.containerMenu==p.inventoryMenu && CreativeTabletAccess.ticketCount()==0,"Tampered binding must not start a chunk request");
                p.setItemInHand(HAND,p.getInventory().removeItemNoUpdate(23));use(p);
                next(Step.NETWORK);
            }
            case NETWORK -> {
                if(!(p.containerMenu instanceof StoreMenu m) || m.capacity()==0)return false;
                require(m.creativeTabletOpening() && m.capacity()==216 && p.level().dimension()==Level.NETHER && !cold(p,CUBE),
                    "See only the owned 216-slot inventory, excluding the neighboring private crate");
                previousShards=shards(p);
                m.action(new Packets.Action(m.containerId,m.session(),m.revision(),"text","amethyst shard",0));
                next(Step.SEARCH);
            }
            case SEARCH -> {
                if(!(p.containerMenu instanceof StoreMenu m) || !m.getSlot(0).getItem().is(Items.AMETHYST_SHARD))return false;
                m.action(new Packets.Action(m.containerId,m.session(),m.revision(),"slot","",256));next(Step.TAKE);
            }
            case TAKE -> {
                if(ticks<2)return false;
                require(shards(p)==previousShards+1 && ((StorageEntity)p.getServer().overworld().getBlockEntity(CUBE)).getItem(215).getCount()==16,
                    "One cross-dimension withdrawal from the real home chest");
                p.getInventory().setItem(23,p.getMainHandItem());p.setItemInHand(HAND,ItemStack.EMPTY);next(Step.DROP);
            }
            case DROP -> {
                if(p.containerMenu!=p.inventoryMenu || CreativeTabletAccess.ticketCount()!=0)return false;
                p.setItemInHand(HAND,p.getInventory().removeItemNoUpdate(23));use(p);next(Step.CLOSED);
            }
            case CLOSED -> {
                if(!(p.containerMenu instanceof StoreMenu m) || m.capacity()==0)return false;
                require(m.capacity()==216,"Private crate stayed hidden on reopening");
                var h=home(p);h.owner=UUID.randomUUID();h.profileChanged();
                next(Step.RESTORE);
            }
            case RESTORE -> {
                if(p.containerMenu!=p.inventoryMenu || CreativeTabletAccess.ticketCount()!=0)return false;
                use(p);next(Step.DENY_RIGHTS);
            }
            case DENY_RIGHTS -> {
                if(ticks<24 || CreativeTabletAccess.ticketCount()!=0)return false;
                require(p.containerMenu==p.inventoryMenu,"Revoked Creative player cannot reopen storage");
                var h=home(p);h.owner=p.getUUID();h.profileChanged();
                p.getInventory().setItem(23,p.getMainHandItem());p.setItemInHand(HAND,p.getInventory().removeItemNoUpdate(24));
                next(Step.COLD_POCKET);
            }
            case COLD_POCKET -> {
                if(!cold(p,HUB) || !cold(p,CUBE) || !cold(p,ENTRANCE))return false;
                use(p);next(Step.POCKET);
            }
            case POCKET -> {
                if(!(p.containerMenu instanceof StoreMenu m) || m.capacity()!=216)return false;
                require(m.hasPocket(pocketId) && m.creativeTabletOpening() && p.level().dimension()==Level.NETHER,"Remote pocket should never teleport");
                p.setGameMode(GameType.SURVIVAL);next(Step.DOWNGRADE);
            }
            case DOWNGRADE -> {
                if(p.containerMenu!=p.inventoryMenu || CreativeTabletAccess.ticketCount()!=0 || PocketService.lease(p.getUUID())!=null)return false;
                use(p);next(Step.SURVIVAL);
            }
            case SURVIVAL -> {
                if(ticks<20)return false;
                require(p.containerMenu==p.inventoryMenu && CreativeTabletAccess.ticketCount()==0 && PocketService.ownedTicketRequests()==0,
                        "Survival must never open or retain Creative access");
                next(Step.CLEAN);
            }
            case CLEAN -> {next(Step.DONE);}
            case DONE -> {return true;}
        }
        return false;
    }
    static boolean run(Minecraft mc) throws IOException {
        if(task!=null){
            if(!task.isDone())return false;
            boolean done=task.join();task=null;
            if(done){
                Files.writeString(mc.gameDirectory.toPath().resolve("creative-tablet.txt"),
                    "Creative non-operator in Nether. Overworld hub and neighboring chest confirmed unloaded first. Genuine creative binding retrieved 1 shard from 17, leaving 16 at home. Revoke, removed tablet, forged coordinates, Survival, released tickets, separate 216-slot pocket and preserved legacy binding verified.\n");
                return true;
            }
        }
        if(mc.player==null || mc.level==null)return false;
        var server=mc.getSingleplayerServer();var id=mc.player.getUUID();
        task=server.submit(()->tick(server.getPlayerList().getPlayer(id)));return false;
    }
    private CreativeAnywhereCheck(){}
}
