package dev.anya.storage.pocket;

import dev.anya.storage.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import java.io.IOException;
import java.util.*;

/** Experimental, server-authoritative pocket runtime. No inventory serialization, persistent tickets or live renderer. */
public final class PocketService {
    public static final ResourceKey<Level> DIMENSION=ResourceKey.create(Registries.DIMENSION,ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID,"pockets"));
    private static final TicketType<UUID> TICKET=TicketType.create("anyastorage_pocket_lease",UUID::compareTo,80);
    private static final PocketLeases LEASES=new PocketLeases();
    private record Ticket(ResourceKey<Level> dimension,ChunkPos chunk,UUID pocket){}
    private record Pending(ServerPlayer player,UUID pocket,PocketLeases.Kind kind,boolean tablet){}
    private record Placement(BlockPos pos,BlockState state){}
    private static final class Preparation {List<Placement> blocks;int cursor;boolean began;}
    private static final Map<UUID,Pending> PENDING=new LinkedHashMap<>();
    private static final Map<UUID,ServerPlayer> RECOVERY=new LinkedHashMap<>();
    private static final Map<UUID,Long> RETRY=new HashMap<>();
    private static final Map<UUID,Preparation> PREPARING=new HashMap<>();
    private static final Set<Ticket> TICKETS=new HashSet<>();
    private static final Map<Ticket,Long> RECOVERY_TICKETS=new HashMap<>();
    private static final ThreadLocal<Boolean> TELEPORT=ThreadLocal.withInitial(()->false);
    private static MinecraftServer currentServer;
    private static PocketLedger ledger;
    private static IOException failure;
    private static long clock;
    private static int lastPreparedBlocks;
    public static boolean enabled(){return Config.pocketStorageEnabled();}

    public static void validateStorageChange(MinecraftServer server, boolean enabling) {
        if (enabling && dimension(server) == null)
            throw new IllegalArgumentException("The pocket dimension is missing. Load the matching mod data before enabling pockets.");
        if (!enabling && currentServer == server && !PREPARING.isEmpty())
            throw new IllegalArgumentException("A pocket room is being prepared. Wait for it to finish before disabling pockets.");
    }
    public static void storageDisabled(MinecraftServer server) {
        if (currentServer != server) return;
        for (var lease : LEASES.snapshot()) {
            UUID player = lease.player();
            endLease(player, leasePlayer(server, player, PENDING.get(player)));
        }
        syncTickets(server);
    }

    public static boolean inPocket(Level level){return level.dimension().equals(DIMENSION);}
    public static boolean ownTeleport(){return TELEPORT.get();}
    public static PocketLedger ledger(MinecraftServer server)throws IOException {
        if(currentServer!=server){if(currentServer!=null)stop(currentServer);currentServer=server;}
        if(failure!=null)throw new IOException("Pocket ledger unavailable; existing data was not reset",failure);
        if(ledger==null)try{ledger=new PocketLedger(server.getWorldPath(LevelResource.ROOT));}catch(IOException e){failure=e;throw e;}
        return ledger;
    }
    public static ServerLevel dimension(MinecraftServer server){return server.getLevel(DIMENSION);}
    static PocketLedger.Pocket loadedPocket(MinecraftServer server,UUID id){
        return currentServer==server&&failure==null&&ledger!=null&&id!=null?ledger.get(id):null;
    }
    private static ServerLevel level(MinecraftServer server,String id){return server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(id)));}
    static boolean present(ServerPlayer p){return p.getServer()!=null&&(p.getServer().getPlayerList().getPlayer(p.getUUID())==p||(Boolean.getBoolean("anyastorage.pocketTests")&&p instanceof FakePlayer));}
    public static String status(){return "Experimental pocket gate="+enabled()+"; active pockets="+LEASES.active().size()+"; leases="+LEASES.snapshot().size()+"; Anya-owned ticket requests="+TICKETS.size()+"; last preparation writes="+lastPreparedBlocks+" (not total engine chunks)";}
    private static String disabledHint() {
        return "Pocket storage is off. An operator can use /anya pocket enable on the test server.";
    }
    private static void error(ServerPlayer p,Exception e){AnyaStorage.tell(p,e.getMessage()==null?"Pocket action denied":e.getMessage());AnyaStorage.LOG.warn("Pocket action stopped for {}: {}",p.getUUID(),e.toString());}
    public static StorageEntity home(ServerPlayer p,PocketLedger.Pocket pocket){
        var a=pocket.anchor();ServerLevel home=level(p.getServer(),a.dimension());BlockPos pos=BlockPos.of(a.hub());
        if(home==null||!home.hasChunkAt(pos)||!home.hasChunkAt(BlockPos.of(a.entrance())))return null;
        if(!(home.getBlockEntity(pos) instanceof StorageEntity hub)||!hub.kind().equals("hub")||!a.hubId().equals(hub.existingStorageIdentity())||!Objects.equals(hub.owner,pocket.owner()))return null;
        if(!(home.getBlockEntity(BlockPos.of(a.entrance())) instanceof PocketContent.EntranceEntity entrance)||!Objects.equals(entrance.pocket,pocket.id())||!Objects.equals(entrance.owner,pocket.owner()))return null;
        return hub;
    }
    /** Loaded-only home checks. The radius stays spherical; claims can restrict, never widen it. */
    public static String authorize(ServerPlayer p,PocketLedger.Pocket pocket,PocketLeases.Kind kind,boolean arriving,int rights){
        if(!enabled())return disabledHint();
        if(!p.isAlive()||p.isSpectator())return "Player cannot interact";
        if(kind==PocketLeases.Kind.REMOTE && REMOTE_TABLETS.containsKey(p.getUUID())){
            ItemStack active=REMOTE_TABLETS.get(p.getUUID());var binding=BagInventory.data(active);
            if((p.getMainHandItem()!=active&&p.getOffhandItem()!=active)||!StorageItem.permittedTablet(p,active)
                    ||!binding.hasUUID("PocketId")||!binding.getUUID("PocketId").equals(pocket.id())
                    ||binding.getLong("HubPos")!=pocket.anchor().hub()||!binding.getString("HubDimension").equals(pocket.anchor().dimension()))
                return "The linked tablet is no longer held";
        }
        if(arriving&&kind==PocketLeases.Kind.VISIT){String travel=PocketTravelGuard.reason(p);if(!travel.isEmpty())return travel;}
        StorageEntity hub=home(p,pocket);if(hub==null)return "Loaded home hub/entrance identity is missing or changed";
        String reason=homeAccess(p,pocket,hub,rights|(kind==PocketLeases.Kind.VISIT?BasePermissions.VISIT:0));
        if(!reason.isEmpty())return reason;
        if(kind==PocketLeases.Kind.REMOTE||arriving){
            if(kind==PocketLeases.Kind.REMOTE && CreativeTabletAccess.pocketSession(p,pocket.id())){
                if(!CreativeTabletAccess.permitsPocket(p,pocket,hub))return "UwU Tablet access ended";
            }else if(!Network.inRange(p,hub))return "Outside the loaded home hub's spherical radius";
            if(kind==PocketLeases.Kind.VISIT&&p.distanceToSqr(Vec3.atCenterOf(BlockPos.of(pocket.anchor().entrance())))>64)return "Stand near the entrance to visit";
        }else if(!inPocket(p.level())||!pocket.contains(p.blockPosition().getX(),p.blockPosition().getY(),p.blockPosition().getZ()))return "Outside your authorized pocket boundary";
        return "";
    }
    static String homeAccess(ServerPlayer p,PocketLedger.Pocket pocket,StorageEntity hub,int rights){
        if(!hub.allowed(p)||!hub.permits(p,rights))return "Pocket permission denied; Visit is separate from storage rights";
        ServerLevel home=(ServerLevel)hub.getLevel();
        String reason=Safety.locationReason(p,home,hub.getBlockPos());if(!reason.isEmpty())return reason;
        return Safety.locationReason(p,home,BlockPos.of(pocket.anchor().entrance()));
    }
    public static void link(ServerPlayer p,PocketContent.EntranceEntity entrance,ItemStack remote){
        try{
            if(!enabled())throw new IOException(disabledHint());
            if(!StorageItem.isTablet(remote)||!StorageItem.permittedTablet(p,remote)||(p.getMainHandItem()!=remote&&p.getOffhandItem()!=remote))throw new IOException("Hold your tablet first.");
            if(inPocket(p.level())||p.isSpectator()||!p.isAlive()||entrance.getLevel()!=p.level()||p.distanceToSqr(Vec3.atCenterOf(entrance.getBlockPos()))>64)throw new IOException("Invalid entrance interaction");
            StorageEntity hub=StorageItem.bound(p,remote);if(hub==null||!CreativeTabletAccess.priorHubBinding(p,remote,hub))throw new IOException("Bind your tablet to a loaded home hub first.");
            if(!Objects.equals(hub.owner,p.getUUID())||!Objects.equals(entrance.owner,p.getUUID()))throw new IOException("The same player must own the hub and entrance");
            String denied=Safety.locationReason(p,p.serverLevel(),entrance.getBlockPos());if(!denied.isEmpty())throw new IOException(denied);
            PocketLedger l=ledger(p.getServer());PocketLedger.Pocket pocket;
            if(entrance.pocket==null){
                if(l.pockets().stream().anyMatch(r->r.anchor().dimension().equals(p.level().dimension().location().toString())&&r.anchor().entrance()==entrance.getBlockPos().asLong()))throw new IOException("This position has an existing pocket. Use owner recovery, not a new allocation");
                var anchor=new PocketLedger.Anchor(p.level().dimension().location().toString(),hub.getBlockPos().asLong(),hub.storageIdentity(),entrance.getBlockPos().asLong());
                pocket=l.reserve(p.getUUID(),anchor,"galaxy",ExpansionRules.get().pocketLimit);
                entrance.pocket=pocket.id();entrance.setChanged();
            }else{pocket=l.get(entrance.pocket);if(pocket==null||home(p,pocket)!=hub)throw new IOException("Copied or moved entrance refused; owner reanchor required");}
            var data=BagInventory.data(remote);
            if(data.hasUUID("PocketId") && pocket.id().equals(data.getUUID("PocketId"))){
                CreativeTabletAccess.bindPocket(p,remote,hub,pocket);return;
            }
            data.putUUID("PocketId",pocket.id());BagInventory.data(remote,data);CreativeTabletAccess.bindPocket(p,remote,hub,pocket);
            String reach=StorageItem.isCreativeTablet(remote)?"from any dimension":"within the hub radius";
            AnyaStorage.tell(p,"Linked to pocket "+pocket.id()+". Stand up and right-click the cube to visit. Use the tablet in air to open prepared storage "+reach+".");
        }catch(Exception e){error(p,e);}
    }
    public static void reanchor(ServerPlayer p,UUID id,PocketContent.EntranceEntity entrance,ItemStack remote){
        try{
            if(!enabled()||inPocket(p.level()))throw new IOException("Pocket recovery requires the test gate and a home-world entrance");
            PocketLedger l=ledger(p.getServer());var r=l.get(id);StorageEntity hub=StorageItem.bound(p,remote);
            if(!StorageItem.isTablet(remote)||!StorageItem.permittedTablet(p,remote)||(p.getMainHandItem()!=remote&&p.getOffhandItem()!=remote))throw new IOException("Hold your tablet first.");
            if(r==null||!r.owner().equals(p.getUUID())||!Objects.equals(entrance.owner,p.getUUID())||hub==null||!CreativeTabletAccess.priorHubBinding(p,remote,hub)||!Objects.equals(hub.owner,p.getUUID())||entrance.getLevel()!=p.level()||p.distanceToSqr(Vec3.atCenterOf(entrance.getBlockPos()))>64)throw new IOException("Only the recorded owner can reanchor at their nearby entrance and bound hub");
            if(LEASES.active().contains(id))throw new IOException("Let every visitor/storage viewer leave before reanchoring");
            if(entrance.pocket!=null&&!entrance.pocket.equals(id))throw new IOException("Replacement entrance belongs to another pocket");
            String denied=Safety.locationReason(p,p.serverLevel(),entrance.getBlockPos());if(!denied.isEmpty())throw new IOException(denied);
            l.reanchor(id,p.getUUID(),new PocketLedger.Anchor(p.level().dimension().location().toString(),hub.getBlockPos().asLong(),hub.storageIdentity(),entrance.getBlockPos().asLong()));
            entrance.pocket=id;entrance.setChanged();var data=BagInventory.data(remote);data.putUUID("PocketId",id);BagInventory.data(remote,data);CreativeTabletAccess.bindPocket(p,remote,hub,l.get(id));
            AnyaStorage.tell(p,"Existing pocket reanchored. Its cell and chest contents were not copied, reset or regenerated.");
        }catch(Exception e){error(p,e);}
    }
    public static void theme(ServerPlayer p,UUID id,String theme){
        try {
            if(!enabled()||id==null||LEASES.active().contains(id))throw new IOException("Choose scenery at your linked, inactive entrance before first visit");
            var l=ledger(p.getServer());var r=l.get(id);
            if(r==null||!authorize(p,r,PocketLeases.Kind.VISIT,true,0).isEmpty())throw new IOException("Entrance access denied");
            l.theme(id,p.getUUID(),theme);AnyaStorage.tell(p,"Reserved scenery: "+theme+". It becomes fixed once room preparation begins; no storage blocks are restyled.");
        }catch(Exception e){error(p,e);}
    }
    public static void enter(ServerPlayer p,PocketContent.EntranceEntity entrance){
        try{
            if(!enabled())throw new IOException(disabledHint());
            if(entrance.pocket==null)throw new IOException("Bind a Cupid Tablet or UwU Tablet to the home hub, then sneak-use it on this Skygrid Cube.");
            var l=ledger(p.getServer());var r=l.get(entrance.pocket);
            if(r==null||entrance.getLevel()!=p.level()||r.anchor().entrance()!=entrance.getBlockPos().asLong())throw new IOException("Unrecognized entrance identity");
            if(l.returning(p.getUUID())!=null){recover(p);return;}
            if(p.isPassenger()||p.isVehicle())throw new IOException("Dismount and leave passengers outside before entering");
            start(p,r,PocketLeases.Kind.VISIT,false);
        }catch(Exception e){error(p,e);}
    }
    /** A linked tablet used inside a pocket reuses the current physical visit. */
    public static boolean openVisitedTablet(ServerPlayer player, net.minecraft.world.InteractionHand hand) {
        if (!inPocket(player.level())) return false;
        ItemStack tablet = player.getItemInHand(hand);
        var data = BagInventory.data(tablet);
        if (!data.hasUUID("PocketId")) return false;
        try {
            var lease = lease(player.getUUID());
            var room = loadedPocket(player.getServer(),data.getUUID("PocketId"));
            if (lease==null || room==null || !lease.pocket().equals(room.id()) || lease.kind()!=PocketLeases.Kind.VISIT
                    || player.containerMenu!=player.inventoryMenu)
                throw new IOException("Use a tablet linked to the room you are visiting.");
            var view = PocketView.localTablet(player,room,hand);
            StoreMenu.visitTablet(player,view,StorageItem.isCreativeTablet(tablet));
        } catch (IOException denied) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(denied.getMessage()),true);
        }
        return true;
    }
    /** Returns true when a pocket-bound remote was handled (including denials), never falls back to a different inventory. */
    public static boolean remote(ServerPlayer p,ItemStack stack,StorageEntity hub){
        var tag=BagInventory.data(stack);if(!tag.hasUUID("PocketId"))return false;
        try{
            if(!StorageItem.isTablet(stack)||!StorageItem.permittedTablet(p,stack)||(p.getMainHandItem()!=stack && p.getOffhandItem()!=stack))
                throw new IOException("Hold the linked tablet first.");
            var r=ledger(p.getServer()).get(tag.getUUID("PocketId"));if(r==null||home(p,r)!=hub)throw new IOException("Pocket remote binding is stale");start(p,r,PocketLeases.Kind.REMOTE,true);REMOTE_TABLETS.put(p.getUUID(),stack);}catch(Exception e){error(p,e);}return true;
    }
    private static void start(ServerPlayer p,PocketLedger.Pocket r,PocketLeases.Kind kind,boolean tablet)throws IOException {
        Pending pending=PENDING.get(p.getUUID());
        if(pending!=null && pending.player()==p && pending.pocket().equals(r.id()) && pending.kind()==kind)return;
        if(pending!=null||LEASES.get(p.getUUID())!=null)throw new IOException("A pocket operation is already active");
        String denied=authorize(p,r,kind,true,0);if(!denied.isEmpty())throw new IOException(denied);
        if(dimension(p.getServer())==null)throw new IOException("Pocket dimension is missing; restart a disposable world with the matching data pack");
        if(r.stage()==PocketLedger.Stage.BUILDING&&!PREPARING.containsKey(r.id()))throw new IOException("Interrupted room preparation: fail-closed, no regeneration. Preserve this world for manual inspection");
        if(kind==PocketLeases.Kind.REMOTE&&r.stage()!=PocketLedger.Stage.READY)throw new IOException("Visit once to prepare the pocket before opening remote storage");
        p.closeContainer(); // Close an older view before creating the new lease.
        if(!LEASES.acquire(p.getUUID(),r.id(),kind,r.epoch(),clock,ExpansionRules.get().activePocketLimit,16,80,ExpansionRules.get().pocketSessionTicks))throw new IOException("Active-pocket or player budget reached");
        PENDING.put(p.getUUID(),new Pending(p,r.id(),kind,tablet));syncTickets(p.getServer());
    }
    private static Set<Ticket> pocketTickets(PocketLedger.Pocket p){
        Set<Ticket> out=new HashSet<>();var key=ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(p.anchor().dimension()));
        out.add(new Ticket(key,new ChunkPos(BlockPos.of(p.anchor().hub())),p.id()));out.add(new Ticket(key,new ChunkPos(BlockPos.of(p.anchor().entrance())),p.id()));
        // Four build chunks plus a one-chunk structural boundary ring: sixteen destination requests, not four.
        for(int x=(p.x()-1)>>4;x<=(p.x()+32)>>4;x++)for(int z=(p.z()-1)>>4;z<=(p.z()+32)>>4;z++)out.add(new Ticket(DIMENSION,new ChunkPos(x,z),p.id()));
        return out;
    }
    private static void syncTickets(MinecraftServer server){
        Set<Ticket> desired=new HashSet<>();
        if(ledger!=null)for(UUID id:LEASES.active()){var p=ledger.get(id);if(p!=null)desired.addAll(pocketTickets(p));}
        RECOVERY_TICKETS.entrySet().removeIf(e->clock>=e.getValue());desired.addAll(RECOVERY_TICKETS.keySet());
        for(Ticket t:new HashSet<>(TICKETS))if(!desired.contains(t)){ServerLevel l=server.getLevel(t.dimension());if(l!=null)l.getChunkSource().removeRegionTicket(TICKET,t.chunk(),2,t.pocket());TICKETS.remove(t);}
        for(Ticket t:desired){ServerLevel l=server.getLevel(t.dimension());if(l==null)continue;if(!TICKETS.contains(t)||clock%20==0)l.getChunkSource().addRegionTicket(TICKET,t.chunk(),2,t.pocket());TICKETS.add(t);}
    }
    private static List<Placement> shell(PocketLedger.Pocket p){
        BlockState floor=PocketContent.TILE.get().defaultBlockState();
        BlockState wall=PocketContent.PANEL.get().defaultBlockState();
        List<Placement> out=new ArrayList<>();
        for(int x=-1;x<=32;x++)for(int z=-1;z<=32;z++){
            out.add(new Placement(new BlockPos(p.x()+x,64,p.z()+z),floor));out.add(new Placement(new BlockPos(p.x()+x,97,p.z()+z),Blocks.BARRIER.defaultBlockState()));
            if(x==-1||x==32||z==-1||z==32)for(int y=65;y<97;y++)out.add(new Placement(new BlockPos(p.x()+x,y,p.z()+z),(y==68 || y==81) && Math.floorMod(x+z,8)==0?PocketContent.LIGHT.get().defaultBlockState():wall));
        }
        return out;
    }
    public static List<BlockPos> chestPositions(PocketLedger.Pocket p){
        return RoomBounds.STARTER_CHEST_X.stream()
                .map(x -> new BlockPos(p.x() + x, RoomBounds.CHEST_Y, p.z() + RoomBounds.STARTER_CHEST_Z))
                .toList();
    }
    public static BlockPos exitPosition(PocketLedger.Pocket p){return new BlockPos(p.x()+16,65,p.z()+28);}
    private static boolean prepare(MinecraftServer server,PocketLedger.Pocket p)throws IOException {
        ServerLevel level=dimension(server);if(level==null)throw new IOException("Pocket dimension missing");
        // READY describes the saved room. Its existing lease tickets may still be promoting
        // the four plot chunks to the FULL state used by inventory discovery.
        if(p.stage()==PocketLedger.Stage.READY)return new LoadedPocketBlocks(level).fullPlot(p);
        for(Ticket t:pocketTickets(p))if(t.dimension().equals(DIMENSION)&&level.getChunkSource().getChunkNow(t.chunk().x,t.chunk().z)==null){level.getChunk(t.chunk().x,t.chunk().z);return false;} // at most one synchronous chunk request per global preparation tick
        Preparation job=PREPARING.computeIfAbsent(p.id(),id->new Preparation());
        if(!job.began){
            if(p.stage()!=PocketLedger.Stage.RESERVED)throw new IOException("Interrupted preparation cannot be replayed");
            // A newly allocated room must never adopt an inventory already present in its plot.
            for(int x=p.x()>>4;x<=(p.x()+31)>>4;x++)for(int z=p.z()>>4;z<=(p.z()+31)>>4;z++){
                var chunk=level.getChunkSource().getChunkNow(x,z);
                if(chunk==null)return false;
                for(var pos:chunk.getBlockEntitiesPos())if(p.contains(pos.getX(),pos.getY(),pos.getZ()))
                    throw new IOException("This Skygrid space already contains a block entity. Its contents were preserved.");
            }
            if(!level.isEmptyBlock(exitPosition(p)))throw new IOException("Exit position is occupied; generation denied");
            ledger.stage(p.id(),PocketLedger.Stage.RESERVED,PocketLedger.Stage.BUILDING);job.blocks=shell(p);job.began=true;
        }
        int budget=256;
        while(budget-->0&&job.cursor<job.blocks.size()){Placement put=job.blocks.get(job.cursor++);if(!level.isEmptyBlock(put.pos()))throw new IOException("Occupied shell position: preparation stopped without overwriting it");level.setBlock(put.pos(),put.state(),3);lastPreparedBlocks++;}
        if(job.cursor<job.blocks.size())return false;
        if(!level.isEmptyBlock(exitPosition(p)))throw new IOException("Exit position changed during preparation; no block was overwritten");
        level.setBlock(exitPosition(p),Blocks.LODESTONE.defaultBlockState(),3);
        level.getChunkSource().save(true); // commit newly built region data before publishing READY
        ledger.stage(p.id(),PocketLedger.Stage.BUILDING,PocketLedger.Stage.READY);
        PREPARING.remove(p.id());
        return new LoadedPocketBlocks(level).fullPlot(p);
    }
    private static void arrive(Pending pending,PocketLedger.Pocket pocket)throws IOException {
        ServerPlayer p=pending.player();String denied=authorize(p,pocket,pending.kind(),true,0);if(!denied.isEmpty())throw new IOException(denied);
        if(pending.kind()==PocketLeases.Kind.REMOTE){StoreMenu.pocketRemote(p,new PocketView(p,pocket,PocketLeases.Kind.REMOTE),pending.tablet());return;}
        var back=new PocketLedger.ReturnPoint(p.getUUID(),pocket.id(),p.level().dimension().location().toString(),p.getX(),p.getY(),p.getZ(),p.getYRot(),p.getXRot());
        ledger.reserveReturn(back); // durable record is committed BEFORE any teleport
        ServerLevel target=dimension(p.getServer());Vec3 point=new Vec3(pocket.x()+16.5,65,pocket.z()+24.5);
        if(!safe(target,p,point))throw new IOException("Pocket landing is obstructed; the return record is retained");
        teleport(p,target,point,0,0);
        if(!inPocket(p.level()))throw new IOException("Dimension transition was rejected; recovery record retained");
        LEASES.arrived(p.getUUID());
        savePlayer(p);
        boolean owner=pocket.owner().equals(p.getUUID());
        String help;
        var hub=home(p,pocket);
        if(owner || (hub!=null && hub.permitsMember(p.getUUID(),BasePermissions.VISIT | BasePermissions.BUILD))){
            String reason=RoomEditor.modeDenial(p);
            help=reason.isEmpty()?"Right-click with a block to build.":reason;
        }else help="You are visiting. The room owner can grant Build access.";
        AnyaStorage.tell(p,"Welcome to Skygrid. "+help+" Walk through the glowing exit to go home. You can also right-click the lodestone or use Leave room on your tablet.");
    }
    public static void tick(MinecraftServer server){
        clock++;lastPreparedBlocks=0;
        if(currentServer!=null&&currentServer!=server)return;
        // Recovery stays active even when the feature is switched OFF.
        for(ServerPlayer p:server.getPlayerList().getPlayers())if(inPocket(p.level())&&LEASES.get(p.getUUID())==null&&!RECOVERY.containsKey(p.getUUID()))recover(p);
        for(PocketLeases.Lease l:LEASES.snapshot()){
            Pending pending=PENDING.get(l.player());
            ServerPlayer p=leasePlayer(server,l.player(),pending);
            var r=ledger==null?null:ledger.get(l.pocket());
            boolean valid=p!=null&&present(p)&&r!=null&&authorize(p,r,l.kind(),pending!=null,0).isEmpty();
            if(valid&&pending==null&&l.kind()==PocketLeases.Kind.REMOTE)valid=p.containerMenu instanceof StoreMenu m&&m.hasPocket(l.pocket());
            if(valid)valid=LEASES.renew(l.player(),r.epoch(),clock,80);
            if(!valid){
                if(pending!=null && p!=null && clock>=l.deadline())
                    p.displayClientMessage(Component.literal("Skygrid did not finish loading in time. Try opening it again."),true);
                endLease(l.player(),p);
            }
            else if(pending==null && l.kind()==PocketLeases.Kind.VISIT){SkygridWalls.visit(p,r,clock);SkyCeiling.visit(p,r,clock);RoomHologram.visit(p,r,clock);ExitGate.visit(p,r,clock);}
        }
        LEASES.expire(clock);syncTickets(server);
        // One pocket receives preparation work per tick, never one budget per viewer.
        UUID prepared=null;boolean preparedReady=false;
        for(Pending pending:new ArrayList<>(PENDING.values())){
            try{
                var r=ledger.get(pending.pocket());if(r==null)throw new IOException("Pocket identity unavailable");
                if(prepared!=null){if(!prepared.equals(r.id())||!preparedReady)continue;}
                else{prepared=r.id();preparedReady=prepare(server,r);if(!preparedReady)continue;}
                PENDING.remove(pending.player().getUUID());arrive(pending,ledger.get(r.id()));
            }catch(Exception e){PENDING.remove(pending.player().getUUID());release(pending.player().getUUID());error(pending.player(),e);if(inPocket(pending.player().level()))recover(pending.player());}
        }
        PREPARING.keySet().removeIf(id->!LEASES.active().contains(id));
        // At most one recovery request per server tick (at most two bounded destination chunks for its fallback).
        for(var entry:new ArrayList<>(RECOVERY.entrySet())){
            ServerPlayer p=entry.getValue();if(!present(p)){RECOVERY.remove(entry.getKey());continue;}
            if(!p.isAlive()||clock<RETRY.getOrDefault(entry.getKey(),0L))continue;
            try{performRecovery(p);RECOVERY.remove(entry.getKey());RETRY.remove(entry.getKey());}catch(Exception e){RETRY.put(entry.getKey(),clock+100);error(p,e);}break;
        }
        syncTickets(server);
    }
    private static void teleport(ServerPlayer p,ServerLevel level,Vec3 pos,float yaw,float pitch){
        p.closeContainer();p.stopRiding();TELEPORT.set(true);
        try{p.teleportTo(level,pos.x,pos.y,pos.z,yaw,pitch);p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;}finally{TELEPORT.remove();}
    }
    private static void savePlayer(ServerPlayer p)throws IOException {
        if(p instanceof FakePlayer)return; // Isolated test identities have no normal player file.
        p.getServer().getPlayerList().save(p);
        // Vanilla may log a failed save rather than throw. Verify the resulting position before clearing recovery.
        var file=p.getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(p.getUUID()+".dat");
        if(!java.nio.file.Files.isRegularFile(file,java.nio.file.LinkOption.NOFOLLOW_LINKS)||java.nio.file.Files.size(file)>16*1024*1024)throw new IOException("Player save missing or outside bounded verification size; recovery remains recorded");
        var tag=net.minecraft.nbt.NbtIo.readCompressed(file,net.minecraft.nbt.NbtAccounter.create(32L*1024*1024));
        var pos=tag.getList("Pos",net.minecraft.nbt.Tag.TAG_DOUBLE);
        if(!tag.hasUUID("UUID")||!tag.getUUID("UUID").equals(p.getUUID())||!tag.getString("Dimension").equals(p.level().dimension().location().toString())||pos.size()!=3||Math.abs(pos.getDouble(0)-p.getX())>0.01||Math.abs(pos.getDouble(1)-p.getY())>0.01||Math.abs(pos.getDouble(2)-p.getZ())>0.01)throw new IOException("Player position save not confirmed; recovery remains recorded");
    }
    private static boolean safe(ServerLevel level,ServerPlayer p,Vec3 point){
        if(level==null)return false;BlockPos pos=BlockPos.containing(point);
        if(!level.hasChunkAt(pos)||!level.getWorldBorder().isWithinBounds(pos)||!level.getBlockState(pos.below()).isFaceSturdy(level,pos.below(),Direction.UP)||!level.getFluidState(pos).isEmpty()||!level.getFluidState(pos.above()).isEmpty())return false;
        for(BlockPos check:List.of(pos.below(),pos,pos.above())){
            Block b=level.getBlockState(check).getBlock();
            if(b==Blocks.MAGMA_BLOCK||b==Blocks.CAMPFIRE||b==Blocks.SOUL_CAMPFIRE||b==Blocks.FIRE||b==Blocks.SOUL_FIRE||b==Blocks.CACTUS||b==Blocks.SWEET_BERRY_BUSH||b==Blocks.WITHER_ROSE||b==Blocks.POWDER_SNOW||b==Blocks.NETHER_PORTAL||b==Blocks.END_PORTAL||b==Blocks.END_GATEWAY)return false;
        }
        return level.noCollision(p,p.getBoundingBox().move(point.subtract(p.position())));
    }
    private static Vec3 findSafe(ServerLevel level,ServerPlayer p,Vec3 center){
        if(safe(level,p,center))return center;
        for(int r=0;r<=3;r++)for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++)for(int dy=0;dy<=6;dy++){
            Vec3 point=new Vec3(Math.floor(center.x)+dx+.5,Math.floor(center.y)+dy,Math.floor(center.z)+dz+.5);if(safe(level,p,point))return point;
        }return null;
    }
    private static void loadForRecovery(ServerLevel level,BlockPos pos,UUID player){
        Ticket t=new Ticket(level.dimension(),new ChunkPos(pos),player);RECOVERY_TICKETS.put(t,clock+5);syncTickets(level.getServer());
        if(level.getChunkSource().getChunkNow(t.chunk().x,t.chunk().z)==null)level.getChunk(t.chunk().x,t.chunk().z);
    }
    private static void performRecovery(ServerPlayer p)throws IOException {
        PocketLedger l=null;PocketLedger.ReturnPoint back=null;
        try{l=ledger(p.getServer());back=l.returning(p.getUUID());}catch(IOException e){AnyaStorage.LOG.error("Recovering to spawn without resetting unavailable pocket ledger",e);}
        ServerLevel target=back==null?null:level(p.getServer(),back.dimension());Vec3 point=null;
        if(target!=null&&!inPocket(target)){
            var center=new Vec3(back.x(),back.y(),back.z());loadForRecovery(target,BlockPos.containing(center),p.getUUID());point=findSafe(target,p,center);
        }
        if(point==null){
            target=p.getServer().overworld();BlockPos spawn=target.getSharedSpawnPos();loadForRecovery(target,spawn,p.getUUID());
            point=findSafe(target,p,new Vec3(spawn.getX()+.5,target.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,spawn.getX(),spawn.getZ()),spawn.getZ()+.5));
        }
        if(point==null)throw new IOException("No safe return or spawn landing. Recovery remains queued and recorded; administrator assistance required");
        teleport(p,target,point,back==null?0:back.yaw(),back==null?0:back.pitch());
        if(inPocket(p.level())||p.level()!=target)throw new IOException("Return transition rejected; recovery remains recorded");
        savePlayer(p);if(l!=null&&back!=null)l.clearReturn(p.getUUID());
        AnyaStorage.tell(p,"Returned from pocket storage. No player or chest inventory was copied.");
    }
    private static ServerPlayer leasePlayer(MinecraftServer server, UUID player, Pending pending) {
        if (pending != null) return pending.player();
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online == null && Boolean.getBoolean("anyastorage.pocketTests")) return TEST_PLAYERS.get(player);
        return online;
    }

    private static void endLease(UUID player, ServerPlayer online) {
        if (online != null && inPocket(online.level())) {
            recover(online);
        } else {
            release(player);
            if (online != null) online.closeContainer();
        }
    }

    public static boolean visitingOwner(ServerPlayer p,UUID roomId){
        var lease=lease(p.getUUID());
        if(!inPocket(p.level())||lease==null||lease.kind()!=PocketLeases.Kind.VISIT||!lease.pocket().equals(roomId))return false;
        var room=loadedPocket(p.getServer(),roomId);
        return room!=null && room.owner().equals(p.getUUID()) && room.contains(p.getBlockX(),p.getBlockY(),p.getBlockZ())
                && authorize(p,room,PocketLeases.Kind.VISIT,false,0).isEmpty();
    }
    public static void removeGuests(ServerPlayer owner,UUID roomId){
        if(!visitingOwner(owner,roomId))return;
        int count=0;
        for(var visitor:LEASES.snapshot()){
            if(visitor.kind()!=PocketLeases.Kind.VISIT || !visitor.pocket().equals(roomId)
                    || visitor.player().equals(owner.getUUID()) || PENDING.containsKey(visitor.player()))continue;
            ServerPlayer guest=owner.getServer().getPlayerList().getPlayer(visitor.player());
            if(guest!=null && inPocket(guest.level())){recover(guest);count++;}
        }
        owner.displayClientMessage(net.minecraft.network.chat.Component.literal(count==0?"No guests are visiting.":"Returning visiting guests home."),true);
    }
    public static void recover(ServerPlayer p){p.closeContainer();release(p.getUUID());RECOVERY.put(p.getUUID(),p);RETRY.remove(p.getUUID());}
    public static void login(ServerPlayer p){
        try{if(inPocket(p.level())||ledger(p.getServer()).returning(p.getUUID())!=null)recover(p);}catch(IOException e){if(inPocket(p.level()))recover(p);else error(p,e);}
    }
    public static void logout(ServerPlayer p){release(p.getUUID());RECOVERY.remove(p.getUUID());RETRY.remove(p.getUUID());TEST_PLAYERS.remove(p.getUUID());if(p.getServer()!=null)syncTickets(p.getServer());}
    public static void died(ServerPlayer p){p.closeContainer();release(p.getUUID());}
    private static final Map<UUID,ItemStack> REMOTE_TABLETS=new HashMap<>();
    static ItemStack remoteTablet(ServerPlayer player) {return REMOTE_TABLETS.get(player.getUUID());}
    private static void release(UUID player){
        REMOTE_TABLETS.remove(player);
        PENDING.remove(player);
        LEASES.release(player);
        RoomEditor.endVisit(player);
        RoomPlans.endVisit(currentServer,player);
        SkygridWalls.end(player,currentServer);SkyCeiling.end(player,currentServer);ExitGate.end(player);
        RoomHologram.end(currentServer,player);
    }
    public static void closeView(ServerPlayer p){var l=LEASES.get(p.getUUID());if(l!=null&&l.kind()==PocketLeases.Kind.REMOTE)release(p.getUUID());}
    public static PocketLeases.Lease lease(UUID player){return LEASES.get(player);}
    public static boolean recovering(UUID player){return RECOVERY.containsKey(player);}
    public static boolean physicalAllowed(ServerPlayer p,StorageEntity chest){
        if(chest.pocketId==null||!inPocket(p.level())||chest.getLevel()!=p.level())return false;
        try{var r=ledger(p.getServer()).get(chest.pocketId);var l=LEASES.get(p.getUUID());return r!=null&&l!=null&&l.kind()==PocketLeases.Kind.VISIT&&l.pocket().equals(r.id())&&l.epoch()==r.epoch()&&authorize(p,r,l.kind(),false,0).isEmpty()&&PocketRoomLayout.registeredPosition(r,chest.getBlockPos())&&p.distanceToSqr(Vec3.atCenterOf(chest.getBlockPos()))<=64;}catch(IOException e){return false;}
    }
    public static void openChest(ServerPlayer p,StorageEntity chest){
        try{if(!physicalAllowed(p,chest))throw new IOException("Pocket chest access denied");var r=ledger.get(chest.pocketId);StoreMenu.pocketChest(p,new PocketView(p,r,PocketLeases.Kind.VISIT,chest));}catch(Exception e){error(p,e);}
    }
    public static void stop(MinecraftServer server){
        REMOTE_TABLETS.clear();RoomHologram.clear();ExitGate.clear();
        for(Ticket t:TICKETS){ServerLevel l=server.getLevel(t.dimension());if(l!=null)l.getChunkSource().removeRegionTicket(TICKET,t.chunk(),2,t.pocket());}
        TICKETS.clear();RECOVERY_TICKETS.clear();LEASES.clear();PENDING.clear();RECOVERY.clear();RETRY.clear();PREPARING.clear();TEST_PLAYERS.clear();RoomEditor.clear();ledger=null;failure=null;currentServer=null;clock=0;
    }
    // Explicit disposable-test hooks: never reachable through an item, command or client packet.
    private static final Map<UUID,ServerPlayer> TEST_PLAYERS=new HashMap<>();
    public static void testPlayer(ServerPlayer p){if(!Boolean.getBoolean("anyastorage.pocketTests"))throw new IllegalStateException("Not a pocket test launch");TEST_PLAYERS.put(p.getUUID(),p);}
    public static int ownedTicketRequests(){return TICKETS.size();}
    private PocketService(){}
}
