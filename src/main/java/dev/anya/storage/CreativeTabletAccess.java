package dev.anya.storage;

import dev.anya.storage.pocket.PocketLedger;
import dev.anya.storage.pocket.PocketLeases;
import dev.anya.storage.pocket.PocketService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Temporary storage access for one player's currently held, locally bound UwU Tablet. */
@EventBusSubscriber(modid=AnyaStorage.ID)
public final class CreativeTabletAccess {
    private static final TicketType<UUID> HOME_TICKET=TicketType.create("anya_creative_tablet",UUID::compareTo,80);
    private static final Map<UUID,Session> sessions=new HashMap<>();
    private static final int MAX_SESSIONS=8, MAX_BASE_CHUNKS=81, GLOBAL_TICKETS=2*(MAX_BASE_CHUNKS+2);
    private record Binding(String dim,long pos,UUID hub,UUID pocket) {
        static Binding read(CompoundTag tag) {
            if(!tag.hasUUID("HubId")||!tag.contains("HubPos",4)||!tag.contains("HubDimension",8))return null;
            String dim=tag.getString("HubDimension");
            if(dim.length()>256||ResourceLocation.tryParse(dim)==null)return null;
            return new Binding(dim,tag.getLong("HubPos"),tag.getUUID("HubId"),tag.hasUUID("PocketId")?tag.getUUID("PocketId"):null);
        }
        byte[] bytes(){return (dim+"\n"+pos+"\n"+hub+"\n"+pocket).getBytes(StandardCharsets.UTF_8);}
    }
    private static final class SigningKey extends SavedData {
        final byte[] key;
        SigningKey(){key=new byte[32];new SecureRandom().nextBytes(key);setDirty();}
        SigningKey(CompoundTag tag,HolderLookup.Provider lookup){key=tag.getByteArray("Key");}
        @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider lookup){tag.putByteArray("Key",key);return tag;}
        byte[] sign(Binding binding) {
            if(key.length!=32)throw new IllegalStateException("UwU Tablet world key unavailable");
            try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(binding.bytes());}
            catch(java.security.GeneralSecurityException e){throw new IllegalStateException("UwU Tablet signing unavailable",e);}
        }
    }
    private static SigningKey signing(MinecraftServer server){
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(SigningKey::new,SigningKey::new,null),"anya_creative_tablet_key");
    }
    static boolean mayUse(ServerPlayer player, ItemStack tablet) {
        return StorageItem.isCreativeTablet(tablet) && tablet.getCount()==1 && player.isAlive() && !player.isSpectator()
                && player.server.getPlayerList().getPlayer(player.getUUID())==player
                && player.connection!=null && player.connection.getConnection().isConnected();
    }
    public static void bind(ServerPlayer p,ItemStack tablet,StorageEntity hub){
        if(!mayUse(p,tablet)
                ||(p.getMainHandItem()!=tablet&&p.getOffhandItem()!=tablet)||!hub.kind().equals("hub")||!Network.inRange(p,hub)
                ||!p.canInteractWithBlock(hub.getBlockPos(),1)||!Network.hubReason(p,hub).isEmpty())return;
        stamp(p,tablet,hub);
        Network.observeLoadedBase(hub);
    }
    public static void bindPocket(ServerPlayer p,ItemStack tablet,StorageEntity hub,PocketLedger.Pocket room){
        if(!mayUse(p,tablet)||
                (p.getMainHandItem()!=tablet&&p.getOffhandItem()!=tablet)||!Network.inRange(p,hub)||!Objects.equals(room.owner(),p.getUUID())
                ||!p.canInteractWithBlock(BlockPos.of(room.anchor().entrance()),1)||PocketService.home(p,room)!=hub
                ||!room.id().equals(BagInventory.data(tablet).getUUID("PocketId")))return;
        stamp(p,tablet,hub);
    }
    public static boolean priorHubBinding(ServerPlayer p,ItemStack tablet,StorageEntity hub){
        if(!StorageItem.isCreativeTablet(tablet))return true;
        var tag=BagInventory.data(tablet);var binding=Binding.read(tag);
        return binding!=null && binding.pos==hub.getBlockPos().asLong() && binding.hub.equals(hub.existingStorageIdentity())
                && binding.dim.equals(hub.getLevel().dimension().location().toString())
                && MessageDigest.isEqual(tag.getByteArray("CreativeSeal"),signing(p.server).sign(binding));
    }
    private static void stamp(ServerPlayer p,ItemStack tablet,StorageEntity hub){
        var tag=BagInventory.data(tablet);
        if(!tag.getString("HubDimension").equals(p.level().dimension().location().toString())||tag.getLong("HubPos")!=hub.getBlockPos().asLong())return;
        tag.putUUID("HubId",hub.storageIdentity());
        var binding=Binding.read(tag);
        if(binding==null)return;
        tag.putByteArray("CreativeSeal",signing(p.server).sign(binding));
        BagInventory.data(tablet,tag);
    }

    static final class Session {
        final ServerPlayer player; final InteractionHand hand; final ItemStack stack; final CustomData components;
        final ServerLevel home; final Binding binding; final BlockPos hubPos;
        final Set<ChunkPos> tickets=new LinkedHashSet<>(); final long started;
        final Map<ChunkPos,CompletableFuture<Boolean>> existingChunks=new HashMap<>();
        StorageEntity hub; long profile; List<ChunkPos> destinations; boolean requested;
        StoreMenu menu;
        Session(ServerPlayer player, InteractionHand hand, ServerLevel home,Binding binding) {
            this.player=player; this.hand=hand; this.stack=player.getItemInHand(hand); this.components=stack.get(DataComponents.CUSTOM_DATA);
            this.home=home; this.binding=binding; hubPos=BlockPos.of(binding.pos); started=player.server.overworld().getGameTime();
        }
        boolean held() {
            return sessions.get(player.getUUID())==this && mayUse(player,stack) && player.getItemInHand(hand)==stack
                    && stack.get(DataComponents.CUSTOM_DATA)==components;
        }
        boolean matchesHub(ServerPlayer p,StorageEntity target) {
            return p==player && held() && target!=null && hub==target && target.getLevel()==home
                    && !target.isRemoved() && home.hasChunkAt(hubPos) && home.getBlockEntity(hubPos)==target
                    && binding.hub.equals(target.existingStorageIdentity()) && target.profileRevision==profile;
        }
        boolean add(ChunkPos pos) {
            if(tickets.contains(pos))return true;
            if(ticketCount()>=GLOBAL_TICKETS)return false;
            if(!ready(this,pos)){
                var existing=existingChunks.computeIfAbsent(pos,chunk->home.getChunkSource().chunkMap.read(chunk)
                        .thenApply(tag->tag.isPresent() && Set.of("full","minecraft:full").contains(tag.get().getString("Status")))
                        .exceptionally(error->false));
                if(!existing.isDone())return true;
                if(!existing.getNow(false))return false;
            }
            tickets.add(pos);home.getChunkSource().addRegionTicket(HOME_TICKET,pos,0,player.getUUID());return true;
        }
    }
    static Session session(ServerPlayer p){return sessions.get(p.getUUID());}
    public static int ticketCount(){return sessions.values().stream().mapToInt(s->s.tickets.size()).sum();}
    public static boolean pocketSession(ServerPlayer p,UUID id) {
        Session s=session(p);return s!=null&&s.requested&&id.equals(s.binding.pocket);
    }
    public static boolean permitsPocket(ServerPlayer p,PocketLedger.Pocket room,StorageEntity hub){
        Session s=session(p);return s!=null&&room.id().equals(s.binding.pocket)&&s.matchesHub(p,hub);
    }
    public static void open(ServerPlayer p,InteractionHand hand) {
        var tablet=p.getItemInHand(hand);
        if(!mayUse(p,tablet))return;
        if(p.containerMenu!=p.inventoryMenu||session(p)!=null)return;
        var tag=BagInventory.data(tablet);var binding=Binding.read(tag);
        if(binding==null||!MessageDigest.isEqual(tag.getByteArray("CreativeSeal"),signing(p.server).sign(binding))){
            hint(p,"Bind this UwU Tablet to the hub in person first.");return;
        }
        var home=p.server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(binding.dim)));
        BlockPos pos=BlockPos.of(binding.pos);
        if(!Config.get().network || home==null || !home.isInWorldBounds(pos) || !home.getWorldBorder().isWithinBounds(pos)
                || p.server.isUnderSpawnProtection(home,pos,p) || (binding.pocket!=null && (!PocketService.enabled()||PocketService.lease(p.getUUID())!=null))){
            hint(p,"The linked storage is unavailable.");return;
        }
        if(sessions.size()>=MAX_SESSIONS){hint(p,"UwU Tablet session limit reached.");return;}
        var s=new Session(p,hand,home,binding);sessions.put(p.getUUID(),s);
        progress(s);
    }
    static void finish(ServerPlayer p,StoreMenu closed){
        Session s=session(p);
        if(s!=null && s.menu==closed)release(s);
    }
    private static void release(Session s) {
        if(!sessions.remove(s.player.getUUID(),s))return;
        for(var chunk:s.tickets)s.home.getChunkSource().removeRegionTicket(HOME_TICKET,chunk,0,s.player.getUUID());
        if(s.requested && s.binding.pocket!=null)PocketService.closeView(s.player);
    }
    private static void stop(Session s,String why) {
        boolean showing=s.player.containerMenu instanceof StoreMenu menu && menu.usesCreativeSession(s);
        release(s);
        if(showing)s.player.closeContainer();
        if(why!=null && s.player.connection.getConnection().isConnected())hint(s.player,why);
    }
    private static void hint(ServerPlayer p,String text){p.displayClientMessage(Component.literal(text),true);}
    @SubscribeEvent public static void tick(ServerTickEvent.Post e){
        for(var s:List.copyOf(sessions.values()))if(s.player.server==e.getServer())progress(s);
    }
    @SubscribeEvent public static void shutdown(ServerStoppingEvent e){
        for(var s:List.copyOf(sessions.values()))if(s.player.server==e.getServer())release(s);
    }
    private static boolean ready(Session s,ChunkPos pos){return s.home.getChunkSource().getChunkNow(pos.x,pos.z)!=null;}
    private static void progress(Session s) {
        var p=s.player;long now=p.server.overworld().getGameTime();
        if(!s.held() || !Config.get().network || now-s.started>(s.menu==null?600:12000)){
            stop(s,null);return;
        }
        if(now%20==0)for(var pos:s.tickets)s.home.getChunkSource().addRegionTicket(HOME_TICKET,pos,0,p.getUUID());
        if(s.menu!=null) {
            if(p.containerMenu!=s.menu||!s.menu.stillValid(p))stop(s,null);
            return;
        }
        if(s.requested) {
            if(p.containerMenu instanceof StoreMenu menu && menu.creativeTabletOpening())s.menu=menu;
            else if(p.containerMenu!=p.inventoryMenu||PocketService.lease(p.getUUID())==null)stop(s,null);
            return;
        }
        if(p.containerMenu!=p.inventoryMenu){stop(s,null);return;}
        var center=new ChunkPos(s.hubPos);
        if(!s.add(center)){stop(s,"The saved hub chunk is unavailable or the UwU Tablet chunk budget is full.");return;}
        if(!ready(s,center))return;
        if(s.hub==null) {
            if(!(s.home.getBlockEntity(s.hubPos) instanceof StorageEntity found)||!found.kind().equals("hub")
                    ||!s.binding.hub.equals(found.existingStorageIdentity())){stop(s,"This hub binding is stale.");return;}
            s.hub=found;s.profile=found.profileRevision;
        }
        if(!Network.hubReason(p,s.hub,s).isEmpty()||!s.held()){stop(s,"Access to the linked storage ended.");return;}
        if(s.binding.pocket!=null) {
            try {
                var r=PocketService.ledger(p.server).get(s.binding.pocket);
                if(r==null||r.stage()!=PocketLedger.Stage.READY||!r.anchor().dimension().equals(s.binding.dim)||r.anchor().hub()!=s.binding.pos
                        ||!r.anchor().hubId().equals(s.binding.hub)||!Objects.equals(s.hub.owner,r.owner())){
                    stop(s,"This linked pocket is unavailable.");return;
                }
                var entrance=new ChunkPos(BlockPos.of(r.anchor().entrance()));
                if(!s.add(entrance)){stop(s,"The saved entrance chunk is unavailable or the UwU Tablet chunk budget is full.");return;}
                if(!ready(s,entrance))return;
                s.requested=true;PocketService.remote(p,s.stack,s.hub);
                if(PocketService.lease(p.getUUID())==null)release(s);
            } catch(java.io.IOException e){stop(s,"Pocket ledger unavailable.");}
            return;
        }
        if(s.destinations==null) {
            Network.observeLoadedBase(s.hub);
            s.destinations=s.hub.knownStorageChunks.stream().map(ChunkPos::new).filter(pos->Network.chunkInRange(s.hub,pos)).toList();
            if(s.destinations.size()>MAX_BASE_CHUNKS){stop(s,"This base exceeds the UwU Tablet chunk budget.");return;}
        }
        int issued=0;
        for(var pos:s.destinations)if(!s.tickets.contains(pos)) {
            if(!s.add(pos)){stop(s,"A saved storage chunk is unavailable or the UwU Tablet chunk budget is full.");return;}
            if(++issued==4)return;
        }
        if(s.destinations.stream().anyMatch(pos->!ready(s,pos)))return;
        Network.observeLoadedBase(s.hub);
        if(s.hub.knownStorageChunks.stream().map(ChunkPos::new).anyMatch(pos->Network.chunkInRange(s.hub,pos)&&!s.tickets.contains(pos))){
            s.destinations=null;return;
        }
        StoreMenu.creativeNetwork(p,s.hub);
        if(p.containerMenu instanceof StoreMenu menu && menu.creativeTabletOpening())s.menu=menu;else release(s);
    }
    private CreativeTabletAccess(){}
}
