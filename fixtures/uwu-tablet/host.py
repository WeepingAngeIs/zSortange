"""Small in-memory Minecraft endpoints for AccessCheck. Production classes are loaded from the candidate."""

SOURCES = {
    "net.minecraft.resources.ResourceLocation": """public record ResourceLocation(String name) {
        public static ResourceLocation parse(String s){return new ResourceLocation(s);}
        public static ResourceLocation tryParse(String s){return s.contains(":")?parse(s):null;}
        @Override public String toString(){return name;}
    }""",
    "net.minecraft.resources.ResourceKey": """public record ResourceKey<T>(ResourceLocation location){
        public static <T> ResourceKey<T> create(ResourceKey<?> registry,ResourceLocation location){return new ResourceKey<>(location);}
    }""",
    "net.minecraft.core.registries.Registries": """public class Registries {
        public static final net.minecraft.resources.ResourceKey<?> DIMENSION=new net.minecraft.resources.ResourceKey<>(
            net.minecraft.resources.ResourceLocation.parse("minecraft:dimension"));
    }""",
    "net.minecraft.nbt.CompoundTag": """public class CompoundTag {
        private final java.util.Map<String,Object> values=new java.util.HashMap<>();
        public CompoundTag copy(){var copy=new CompoundTag();copy.values.putAll(values);return copy;}
        public boolean contains(String k){return values.containsKey(k);}
        public boolean contains(String k,int type){
            return switch(type){case 4->values.get(k) instanceof Long;case 8->values.get(k) instanceof String;default->false;};
        }
        public void putUUID(String k,java.util.UUID v){values.put(k,v);} public java.util.UUID getUUID(String k){return (java.util.UUID)values.get(k);}
        public boolean hasUUID(String k){return values.get(k) instanceof java.util.UUID;}
        public void putLong(String k,long v){values.put(k,v);} public long getLong(String k){return (long)values.getOrDefault(k,0L);}
        public void putString(String k,String v){values.put(k,v);} public String getString(String k){return (String)values.getOrDefault(k,"");}
        public void putByteArray(String k,byte[] v){values.put(k,v.clone());}
        public byte[] getByteArray(String k){return ((byte[])values.getOrDefault(k,new byte[0])).clone();}
    }""",
    "net.minecraft.core.Vec3i": """public class Vec3i {
        private final int x,y,z;
        public Vec3i(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
        public int getX(){return x;} public int getY(){return y;} public int getZ(){return z;}
        public double distSqr(Vec3i o){return (x-o.x)*(x-o.x)+(y-o.y)*(y-o.y)+(z-o.z)*(z-o.z);}
        @Override public boolean equals(Object o){return o instanceof Vec3i v && x==v.x && y==v.y && z==v.z;}
        @Override public int hashCode(){return java.util.Objects.hash(x,y,z);}
    }""",
    "net.minecraft.core.BlockPos": """public class BlockPos extends Vec3i {
        public BlockPos(int x,int y,int z){super(x,y,z);}
        public long asLong(){return ((long)getX()&0x3ffffff)<<38|((long)getZ()&0x3ffffff)<<12|(getY()&0xfff);}
        public static BlockPos of(long v){return new BlockPos((int)(v>>38),(int)(v<<52>>52),(int)(v<<26>>38));}
    }""",
    "net.minecraft.world.level.ChunkPos": """public class ChunkPos {
        public final int x,z;
        public ChunkPos(net.minecraft.core.BlockPos p){x=p.getX()>>4;z=p.getZ()>>4;}
        public ChunkPos(long n){x=(int)n;z=(int)(n>>>32);}
        public static long asLong(int x,int z){return (x&0xffffffffL)|((long)z<<32);}
        public boolean equals(Object other){return other instanceof ChunkPos c && x==c.x && z==c.z;}
        public int hashCode(){return Long.hashCode(asLong(x,z));}
    }""",
    "net.minecraft.network.chat.Component": """public interface Component {
        static MutableComponent literal(String s){return new MutableComponent();}
    }""",
    "net.minecraft.network.chat.MutableComponent": "public class MutableComponent implements Component {}",
    "net.minecraft.world.entity.Entity": "public class Entity {}",
    "net.minecraft.world.entity.player.Player": """public class Player extends net.minecraft.world.entity.Entity {
        public net.minecraft.world.item.ItemStack getItemInHand(net.minecraft.world.InteractionHand h){return null;}
    }""",
    "net.minecraft.world.InteractionResultHolder": """public record InteractionResultHolder<T>(T stack) {
        public static <T>InteractionResultHolder<T> consume(T s){return new InteractionResultHolder<>(s);}
        public static <T>InteractionResultHolder<T> fail(T s){return new InteractionResultHolder<>(s);}
        public static <T>InteractionResultHolder<T> sidedSuccess(T s,boolean client){return new InteractionResultHolder<>(s);}
    }""",
    "net.minecraft.world.inventory.AbstractContainerMenu": "public class AbstractContainerMenu {}",
    "net.minecraft.world.inventory.InventoryMenu": "public class InventoryMenu extends AbstractContainerMenu {}",
    "net.minecraft.world.Container": "public interface Container {}",
    "net.minecraft.world.level.GameType": "public enum GameType { SURVIVAL, CREATIVE, SPECTATOR }",
    "net.minecraft.world.level.Level": """public class Level {
        public final boolean isClientSide=false;
        private final net.minecraft.resources.ResourceKey<Level> key;
        public Level(String name){key=net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.ResourceLocation.parse(name));}
        public net.minecraft.resources.ResourceKey<Level> dimension(){return key;}
    }""",
    "net.minecraft.world.level.block.entity.BlockEntity": "public class BlockEntity {}",
    "net.minecraft.world.level.chunk.LevelChunk": """public class LevelChunk {
        public java.util.Map<net.minecraft.core.BlockPos,net.minecraft.world.level.block.entity.BlockEntity> getBlockEntities(){
            return java.util.Map.of();
        }
    }""",
    "net.minecraft.server.level.TicketType": """public class TicketType<T> {
        public static <T> TicketType<T> create(String name,java.util.Comparator<T> comparator,int timeout){return new TicketType<>();}
    }""",
    "net.minecraft.server.level.ServerChunkCache": """public class ServerChunkCache {
        public final java.util.Set<net.minecraft.world.level.ChunkPos> tickets=new java.util.HashSet<>();
        public final net.minecraft.world.level.chunk.LevelChunk center=new net.minecraft.world.level.chunk.LevelChunk();
        public net.minecraft.world.level.chunk.LevelChunk getChunkNow(int x,int z){return x==0&&z==0?center:null;}
        public <T> void addRegionTicket(TicketType<T> t,net.minecraft.world.level.ChunkPos pos,int radius,T key){tickets.add(pos);}
        public <T> void removeRegionTicket(TicketType<T> t,net.minecraft.world.level.ChunkPos pos,int radius,T key){tickets.remove(pos);}
    }""",
    "net.minecraft.world.level.saveddata.SavedData": """public abstract class SavedData {
        public record Factory<T extends SavedData>(java.util.function.Supplier<T> constructor,
            java.util.function.BiFunction<net.minecraft.nbt.CompoundTag,net.minecraft.core.HolderLookup.Provider,T> load,
            net.minecraft.util.datafix.DataFixTypes type){}
        public void setDirty(){}
    }""",
    "net.minecraft.world.level.storage.DimensionDataStorage": """public class DimensionDataStorage {
        private final java.util.Map<String,net.minecraft.world.level.saveddata.SavedData> data=new java.util.HashMap<>();
        public final java.util.Map<String,net.minecraft.nbt.CompoundTag> saved=new java.util.HashMap<>();
        @SuppressWarnings("unchecked") public <T extends net.minecraft.world.level.saveddata.SavedData>
        T computeIfAbsent(net.minecraft.world.level.saveddata.SavedData.Factory<T> f,String name){
            return (T)data.computeIfAbsent(name,key->saved.containsKey(key)?f.load().apply(saved.get(key),null):f.constructor().get());
        }
    }""",
    "net.minecraft.world.level.border.WorldBorder": """public class WorldBorder {
        public boolean isWithinBounds(net.minecraft.core.BlockPos pos){return true;}
    }""",
    "net.minecraft.server.level.ServerLevel": """public class ServerLevel extends net.minecraft.world.level.Level {
        public final net.minecraft.world.level.storage.DimensionDataStorage data=new net.minecraft.world.level.storage.DimensionDataStorage();
        public final ServerChunkCache chunks=new ServerChunkCache();
        public final java.util.Map<net.minecraft.core.BlockPos,net.minecraft.world.level.block.entity.BlockEntity> cells=new java.util.HashMap<>();
        public ServerLevel(String name){super(name);}
        public long getGameTime(){return 1;}
        public boolean hasChunkAt(net.minecraft.core.BlockPos pos){return chunks.getChunkNow(pos.getX()>>4,pos.getZ()>>4)!=null;}
        public boolean isInWorldBounds(net.minecraft.core.BlockPos pos){return true;}
        public net.minecraft.world.level.border.WorldBorder getWorldBorder(){return new net.minecraft.world.level.border.WorldBorder();}
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(net.minecraft.core.BlockPos pos){return cells.get(pos);}
        public ServerChunkCache getChunkSource(){return chunks;}
        public net.minecraft.world.level.storage.DimensionDataStorage getDataStorage(){return data;}
    }""",
    "net.minecraft.network.Connection": "public class Connection {public boolean live=true; public boolean isConnected(){return live;}}",
    "net.minecraft.server.network.ServerGamePacketListenerImpl": """public class ServerGamePacketListenerImpl {
        private final net.minecraft.network.Connection connection=new net.minecraft.network.Connection();
        public net.minecraft.network.Connection getConnection(){return connection;}
    }""",
    "net.minecraft.server.players.PlayerList": """public class PlayerList {
        public final java.util.Map<java.util.UUID,net.minecraft.server.level.ServerPlayer> players=new java.util.HashMap<>();
        public net.minecraft.server.level.ServerPlayer getPlayer(java.util.UUID id){return players.get(id);}
    }""",
    "net.minecraft.server.MinecraftServer": """public class MinecraftServer {
        public final net.minecraft.server.level.ServerLevel home=new net.minecraft.server.level.ServerLevel("minecraft:overworld");
        public final net.minecraft.server.players.PlayerList list=new net.minecraft.server.players.PlayerList();
        public net.minecraft.server.players.PlayerList getPlayerList(){return list;}
        public net.minecraft.server.level.ServerLevel overworld(){return home;}
        public net.minecraft.server.level.ServerLevel getLevel(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key){
            return key.equals(home.dimension())?home:null;
        }
        public boolean isUnderSpawnProtection(net.minecraft.server.level.ServerLevel l,net.minecraft.core.BlockPos p,
                                              net.minecraft.world.entity.player.Player player){return false;}
    }""",
    "net.minecraft.server.level.ServerPlayer": """public class ServerPlayer extends net.minecraft.world.entity.player.Player {
        public final net.minecraft.server.MinecraftServer server;
        public final java.util.UUID id=java.util.UUID.randomUUID();
        public final net.minecraft.server.network.ServerGamePacketListenerImpl connection=new net.minecraft.server.network.ServerGamePacketListenerImpl();
        public final net.minecraft.world.inventory.InventoryMenu inventoryMenu=new net.minecraft.world.inventory.InventoryMenu();
        public net.minecraft.world.inventory.AbstractContainerMenu containerMenu=inventoryMenu;
        public net.minecraft.world.level.GameType mode=net.minecraft.world.level.GameType.SURVIVAL;
        public net.minecraft.world.item.ItemStack main,off;
        public ServerLevel world; public boolean alive=true; public double x=.5;
        public ServerPlayer(net.minecraft.server.MinecraftServer server){this.server=server;world=server.home;server.list.players.put(id,this);}
        public java.util.UUID getUUID(){return id;}
        public net.minecraft.world.level.Level level(){return world;}
        public ServerLevel serverLevel(){return world;}
        public boolean isAlive(){return alive;}
        public boolean isSpectator(){return mode==net.minecraft.world.level.GameType.SPECTATOR;}
        public double distanceToSqr(double tx,double ty,double tz){return (x-tx)*(x-tx);}
        public boolean canInteractWithBlock(net.minecraft.core.BlockPos p,double extra){return Math.abs(x-p.getX())<5;}
        public net.minecraft.world.item.ItemStack getMainHandItem(){return main;}
        public net.minecraft.world.item.ItemStack getOffhandItem(){return off;}
        public net.minecraft.world.item.ItemStack getItemInHand(net.minecraft.world.InteractionHand hand){
            return hand==net.minecraft.world.InteractionHand.MAIN_HAND?main:off;
        }
        public void closeContainer(){containerMenu=inventoryMenu;}
        public void displayClientMessage(net.minecraft.network.chat.Component message,boolean actionBar){}
    }""",
    "net.minecraft.core.component.DataComponentType": "public class DataComponentType<T> {}",
    "net.minecraft.core.component.DataComponents": """public class DataComponents {
        public static final DataComponentType<net.minecraft.world.item.component.CustomData> CUSTOM_DATA=new DataComponentType<>();
    }""",
    "net.minecraft.world.level.ItemLike": "public interface ItemLike {net.minecraft.world.item.Item asItem();}",
    "net.minecraft.world.item.Item": """public class Item implements net.minecraft.world.level.ItemLike {
        public static class Properties {} public Item(Properties p){} public Item asItem(){return this;}
    }""",
    "net.minecraft.world.item.component.CustomData": "public record CustomData(net.minecraft.nbt.CompoundTag tag) {}",
    "net.minecraft.world.item.ItemStack": """public class ItemStack {
        public final Item item; public int count=1;
        public net.minecraft.world.item.component.CustomData data;
        public ItemStack(Item item){this.item=item;}
        public boolean is(Item test){return test==item;} public int getCount(){return count;}
        @SuppressWarnings("unchecked") public <T>T get(net.minecraft.core.component.DataComponentType<T> type){return (T)data;}
    }""",
    "net.neoforged.neoforge.registries.DeferredItem": "public record DeferredItem<T>(T get) {}",
    "dev.anya.storage.Content": """public class Content {
        public static final net.neoforged.neoforge.registries.DeferredItem<StorageItem>
            CREATIVE_TABLET=item("creative_tablet"), CUPID_TABLET=item("cupid_tablet"), REMOTE=item("remote");
        private static net.neoforged.neoforge.registries.DeferredItem<StorageItem> item(String kind){
            return new net.neoforged.neoforge.registries.DeferredItem<>(new StorageItem(kind,new net.minecraft.world.item.Item.Properties()));
        }
    }""",
    "dev.anya.storage.BagInventory": """public class BagInventory {
        public static net.minecraft.nbt.CompoundTag data(net.minecraft.world.item.ItemStack s){
            return s.data==null?new net.minecraft.nbt.CompoundTag():s.data.tag().copy();
        }
        public static void data(net.minecraft.world.item.ItemStack s,net.minecraft.nbt.CompoundTag tag){
            s.data=new net.minecraft.world.item.component.CustomData(tag.copy());
        }
    }""",
    "dev.anya.storage.Config": """public class Config {
        public static class Data {public boolean network=true; public int maxScanEntries=20,maxContainers=10; public long revision=1;}
        private static final Data data=new Data(); public static Data get(){return data;}
    }""",
    "dev.anya.storage.StorageEntity": """public class StorageEntity extends net.minecraft.world.level.block.entity.BlockEntity
            implements net.minecraft.world.Container {
        final net.minecraft.server.level.ServerLevel home; final net.minecraft.core.BlockPos pos;
        public java.util.UUID identity=java.util.UUID.randomUUID(),owner;
        public final java.util.Set<Long> knownStorageChunks=new java.util.HashSet<>();
        public final java.util.Map<java.util.UUID,Integer> members=new java.util.HashMap<>();
        public long profileRevision; public java.util.UUID pocketId;
        public StorageEntity(net.minecraft.server.level.ServerLevel level,net.minecraft.core.BlockPos pos){
            home=level;this.pos=pos;level.cells.put(pos,this);
        }
        public String kind(){return "hub";} public int radius(){return 8;}
        public boolean isRemoved(){return false;} public void setChanged(){}
        public java.util.UUID storageIdentity(){return identity;} public java.util.UUID existingStorageIdentity(){return identity;}
        public net.minecraft.world.level.Level getLevel(){return home;} public net.minecraft.core.BlockPos getBlockPos(){return pos;}
        public boolean permits(net.minecraft.server.level.ServerPlayer p,int mask){
            return owner.equals(p.id)||members.containsKey(p.id)&&BasePermissions.allows(members.get(p.id),mask);
        }
    }""",
    "dev.anya.storage.Safety": """public class Safety {
        public static boolean homeClaimAllows=true;
        public static String inHubLevel(net.minecraft.server.level.ServerPlayer p,net.minecraft.server.level.ServerLevel level,
                                        net.minecraft.world.level.block.entity.BlockEntity be,StorageEntity hub){
            var target=(StorageEntity)be;
            return homeClaimAllows && level==target.home && target.permits(p,0)?"":"denied";
        }
        public static String reason(net.minecraft.server.level.ServerPlayer p,net.minecraft.world.level.block.entity.BlockEntity be,StorageEntity hub){
            return inHubLevel(p,p.world,be,hub);
        }
    }""",
    "dev.anya.storage.AnyaStorage": """public class AnyaStorage {
        public static void tell(net.minecraft.server.level.ServerPlayer p,String message){}
    }""",
    "dev.anya.storage.pocket.PocketService": """public class PocketService {
        public static boolean openVisitedTablet(net.minecraft.server.level.ServerPlayer p,net.minecraft.world.InteractionHand hand){return false;}
        public static boolean remote(net.minecraft.server.level.ServerPlayer p,net.minecraft.world.item.ItemStack stack,
                                     dev.anya.storage.StorageEntity hub){return false;}
    }""",
    "dev.anya.storage.StoreMenu": """public class StoreMenu extends net.minecraft.world.inventory.AbstractContainerMenu {
        public static int cupidOpens;
        public static void remote(net.minecraft.server.level.ServerPlayer p,StorageEntity hub,boolean tablet){cupidOpens++;}
        final CreativeTabletAccess.Session session;
        StoreMenu(CreativeTabletAccess.Session s){session=s;}
        public static void creativeNetwork(net.minecraft.server.level.ServerPlayer p,StorageEntity hub){
            var s=CreativeTabletAccess.session(p);
            if(s!=null&&s.matchesHub(p,hub))p.containerMenu=new StoreMenu(s);
        }
        public boolean creativeTabletOpening(){return true;}
        public boolean usesCreativeSession(CreativeTabletAccess.Session s){return session==s;}
        public boolean stillValid(net.minecraft.world.entity.player.Player p){
            return Network.hubReason((net.minecraft.server.level.ServerPlayer)p,session.hub,session).isEmpty();
        }
    }""",
    "net.neoforged.neoforge.event.tick.ServerTickEvent": """public class ServerTickEvent {
        public record Post(net.minecraft.server.MinecraftServer getServer) {}
    }""",
}
