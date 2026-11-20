package dev.anya.storage;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

public final class StorageEntity extends BlockEntity implements WorldlyContainer {
    private NonNullList<ItemStack> items;
    public UUID owner;
    public UUID pocketId;
    private UUID storageIdentity;
    public UUID storageIdentity(){if(storageIdentity==null){storageIdentity=UUID.randomUUID();setChanged();}return storageIdentity;}
    /** Looking at an entrance must never initialize a legacy chest or hub. */
    public UUID existingStorageIdentity(){return storageIdentity;}
    public final Set<UUID> trusted=new HashSet<>();
    public final Map<UUID,Integer> memberPermissions=new HashMap<>();
    public final BaseActivity activity=new BaseActivity();
    public String baseName="My Base",symbol="heart";
    public int oreProgress,rangeUpgrades,viewers;
    public long profileRevision;
    public boolean showLabels=true;
    public float openness,previousOpenness;
    private long nextChestChime;
    public final Map<Long,Route> routes=new HashMap<>();
    public static final class Route {
        public String category="any";
        public int priority;
        public boolean overflow=true;
        public final Set<String> items=new TreeSet<>();
        public boolean allows(ItemStack stack) { return (items.isEmpty()||items.contains(Categories.id(stack))) && (category.equals("any")||category.equals(Categories.of(stack))); }
        public boolean designated(){return !category.equals("any")||!items.isEmpty();}
    }
    public StorageEntity(BlockPos pos,BlockState state) { super(Content.STORAGE.get(),pos,state);items=NonNullList.withSize(kind().equals("hub")?1:kind().equals("cube")?216:54,ItemStack.EMPTY); }
    public String kind(){return getBlockState().getBlock() instanceof StorageBlock block?block.kind:"chest";}
    public int radius(){Config.Data d=Config.get();return Math.min(d.maxRadius,d.initialRadius+Math.min(128,rangeUpgrades)*d.radiusStep);}
    public int nextRadius(){return Math.min(Config.get().maxRadius,radius()+Config.get().radiusStep);}
    public int upgradeCost(){return Math.min(1000000,Config.get().upgradeBaseCost*(rangeUpgrades+1));}
    public boolean ownedBy(ServerPlayer p){return p.hasPermissions(2)||Objects.equals(owner,p.getUUID());}
    public int memberMask(UUID id){return trusted.contains(id)?memberPermissions.getOrDefault(id,BasePermissions.LEGACY):0;}
    public boolean permitsMember(UUID id,int rights){return BasePermissions.allows(memberMask(id),rights);}
    public boolean permits(ServerPlayer p,int rights){return ownedBy(p)||BasePermissions.allows(memberMask(p.getUUID()),rights);}
    public boolean allowed(ServerPlayer p){if(pocketId!=null)return dev.anya.storage.pocket.PocketService.physicalAllowed(p,this);return ownedBy(p)||(kind().equals("hub")?memberMask(p.getUUID())>0:trusted.contains(p.getUUID()));}
    public void setMember(UUID id,int rights){
        if(id==null||!BasePermissions.valid(rights))throw new IllegalArgumentException("Invalid member or permission mask");
        if(!trusted.contains(id)&&trusted.size()>=64)throw new IllegalArgumentException("Trust limit reached");
        trusted.add(id);memberPermissions.put(id,rights);profileChanged();
    }
    void toggleMemberRight(UUID id,int right){
        if(!trusted.contains(id))throw new IllegalArgumentException("This member no longer belongs to the base.");
        if(!BasePermissions.valid(right)||Integer.bitCount(right)!=1)throw new IllegalArgumentException("Select one valid permission.");
        memberPermissions.put(id,memberMask(id)^right);
        profileChanged();
    }
    public void removeMember(UUID id){trusted.remove(id);memberPermissions.remove(id);profileChanged();}
    public boolean canReadActivity(ServerPlayer p){return ExpansionRules.get().activityLogging&&(ownedBy(p)||(ExpansionRules.get().activityMemberLogs&&permits(p,BasePermissions.LOG)));}
    public void logTransfer(ServerPlayer p,BaseActivity.Action action,int accepted){
        if(kind().equals("hub")&&activity.record(ExpansionRules.get(),System.currentTimeMillis(),p.getUUID(),action,accepted))setChanged();
    }
    public Route route(BlockPos pos){return routes.getOrDefault(pos.asLong(),new Route());}
    public void profileChanged(){profileRevision++;setChanged();if(level!=null)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);}
    public void openViewer() {
        if (++viewers == 1 && level != null) level.blockEvent(worldPosition, getBlockState().getBlock(), 1, 1);
    }
    public void closeViewer() {
        if (viewers > 0 && --viewers == 0 && level != null) level.blockEvent(worldPosition, getBlockState().getBlock(), 1, 0);
    }
    @Override public boolean triggerEvent(int id, int value) {
        if (id != 1) return super.triggerEvent(id, value);
        if (level != null && level.isClientSide) viewers = value > 0 ? 1 : 0;
        // Server block events can be delayed and deduplicated within a tick.
        return level == null || level.isClientSide || (viewers > 0) == (value > 0);
    }
    public static void tick(Level level,BlockPos pos,BlockState state,StorageEntity be){
        if(!level.isClientSide&&level.getGameTime()%20==0&&be.activity.prune(ExpansionRules.get()))be.setChanged();
        be.previousOpenness=be.openness;be.openness=Math.max(0,Math.min(1,be.openness+(be.viewers>0?0.10f:-0.10f)));
        if (!level.isClientSide && be.getBlockState().is(Content.CRATE.get()) && level.getGameTime() >= be.nextChestChime) {
            boolean opening = be.previousOpenness == 0 && be.openness > 0;
            boolean closed = be.previousOpenness > 0 && be.openness == 0;
            if (opening || closed) {
                level.playSound(null, pos, opening ? Content.CHEST_OPEN.get() : Content.CHEST_CLOSE.get(), SoundSource.BLOCKS, .55f, 1);
                be.nextChestChime = level.getGameTime() + 10;
            }
        }
        if(!level.isClientSide&&be.showLabels!=Config.get().labels){be.showLabels=Config.get().labels;level.sendBlockUpdated(pos,state,state,3);}
    }
    @Override public void onLoad(){super.onLoad();if(level!=null&&!level.isClientSide&&kind().equals("hub"))Network.add(this);}
    @Override public void setRemoved(){Network.remove(this);super.setRemoved();}
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider lookup){
        super.saveAdditional(tag,lookup);ContainerHelper.saveAllItems(tag,items,lookup);tag.putInt("Capacity",items.size());
        if(storageIdentity!=null)tag.putUUID("StorageIdentity",storageIdentity);if(pocketId!=null)tag.putUUID("PocketId",pocketId);
        if(owner!=null)tag.putUUID("Owner",owner);tag.putString("BaseName",baseName);tag.putString("Symbol",symbol);
        tag.putInt("Ore",oreProgress);tag.putInt("RangeUpgrades",rangeUpgrades);tag.putLong("ProfileRevision",profileRevision);
        ListTag trust=new ListTag();trusted.forEach(uuid->trust.add(NbtUtils.createUUID(uuid)));tag.put("Trusted",trust);
        ListTag rights=new ListTag();memberPermissions.forEach((id,mask)->{if(trusted.contains(id)){CompoundTag t=new CompoundTag();t.putUUID("Id",id);t.putInt("Mask",mask);rights.add(t);}});tag.put("MemberPermissions",rights);
        tag.put("Activity",activity.save(ExpansionRules.get()));
        ListTag rules=new ListTag();routes.forEach((pos,route)->{CompoundTag r=new CompoundTag();r.putLong("Pos",pos);r.putString("Category",route.category);r.putInt("Priority",route.priority);r.putBoolean("Overflow",route.overflow);ListTag ids=new ListTag();route.items.forEach(s->ids.add(StringTag.valueOf(s)));r.put("Items",ids);rules.add(r);});tag.put("Routes",rules);
    }
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider lookup){
        super.loadAdditional(tag,lookup);
        int capacity=tag.contains("Capacity")?tag.getInt("Capacity"):items.size();
        if(capacity<1||capacity>4096)throw new IllegalArgumentException("Refusing corrupt storage capacity "+capacity);
        items=NonNullList.withSize(capacity,ItemStack.EMPTY);ContainerHelper.loadAllItems(tag,items,lookup);
        storageIdentity=tag.hasUUID("StorageIdentity")?tag.getUUID("StorageIdentity"):null;pocketId=tag.hasUUID("PocketId")?tag.getUUID("PocketId"):null;
        owner=tag.hasUUID("Owner")?tag.getUUID("Owner"):null;baseName=tag.getString("BaseName");symbol=tag.getString("Symbol");
        if(baseName.isBlank())baseName="My Base";if(!Config.SYMBOLS.contains(symbol))symbol="heart";
        oreProgress=Math.max(0,tag.getInt("Ore"));rangeUpgrades=Math.max(0,tag.getInt("RangeUpgrades"));profileRevision=tag.getLong("ProfileRevision");
        trusted.clear();for(Tag uuid:tag.getList("Trusted",Tag.TAG_INT_ARRAY))try{trusted.add(NbtUtils.loadUUID(uuid));}catch(IllegalArgumentException ignored){}
        memberPermissions.clear();ListTag rights=tag.getList("MemberPermissions",Tag.TAG_COMPOUND);
        if(rights.size()>64)throw new IllegalArgumentException("Refusing oversized member permissions; data was not reset");
        for(int i=0;i<rights.size();i++){CompoundTag t=rights.getCompound(i);if(!t.hasUUID("Id"))continue;UUID id=t.getUUID("Id");int mask=t.contains("Mask",Tag.TAG_INT)?t.getInt("Mask"):-1;
            if(trusted.contains(id))memberPermissions.merge(id,BasePermissions.valid(mask)?mask:0,(a,b)->a&b);}
        activity.load(tag.getList("Activity",Tag.TAG_COMPOUND),ExpansionRules.get());
        routes.clear();ListTag rules=tag.getList("Routes",Tag.TAG_COMPOUND);
        for(int i=0;i<Math.min(512,rules.size());i++){CompoundTag r=rules.getCompound(i);Route route=new Route();route.category=r.getString("Category");if(!Config.CATEGORIES.contains(route.category))route.category="any";route.priority=Math.max(0,Math.min(9,r.getInt("Priority")));route.overflow=r.getBoolean("Overflow");ListTag ids=r.getList("Items",Tag.TAG_STRING);for(int j=0;j<Math.min(64,ids.size());j++)route.items.add(ids.getString(j));routes.put(r.getLong("Pos"),route);}
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider lookup){CompoundTag tag=new CompoundTag();tag.putString("Symbol",symbol);tag.putBoolean("ShowLabels",level!=null&&!level.isClientSide?Config.get().labels:showLabels);if(kind().equals("chest"))tag.putBoolean("LidOpen",viewers>0);return tag;}
    @Override public void handleUpdateTag(CompoundTag tag,HolderLookup.Provider lookup){symbol=tag.getString("Symbol");showLabels=!tag.contains("ShowLabels")||tag.getBoolean("ShowLabels");if(level!=null&&level.isClientSide&&tag.contains("LidOpen"))viewers=tag.getBoolean("LidOpen")?1:0;}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
    @Override public void onDataPacket(net.minecraft.network.Connection connection,ClientboundBlockEntityDataPacket packet,HolderLookup.Provider lookup){if(packet.getTag()!=null)handleUpdateTag(packet.getTag(),lookup);}
    @Override public void setChanged(){
        super.setChanged();
        if(items!=null)FieldKit.chestEdited(this);
    }
    @Override public int getContainerSize(){return items.size();}
    @Override public boolean isEmpty(){return items.stream().allMatch(ItemStack::isEmpty);}
    @Override public ItemStack getItem(int slot){return items.get(slot);}
    @Override public ItemStack removeItem(int slot,int amount){ItemStack out=ContainerHelper.removeItem(items,slot,amount);if(!out.isEmpty())setChanged();return out;}
    @Override public ItemStack removeItemNoUpdate(int slot){ItemStack out=ContainerHelper.takeItem(items,slot);setChanged();return out;}
    @Override public void setItem(int slot,ItemStack stack){items.set(slot,stack);setChanged();}
    @Override public boolean stillValid(Player p){return p instanceof ServerPlayer sp&&allowed(sp)&&!isRemoved()&&p.distanceToSqr(worldPosition.getX()+.5,worldPosition.getY()+.5,worldPosition.getZ()+.5)<=64;}
    @Override public void clearContent(){items.replaceAll(s->ItemStack.EMPTY);setChanged();}
    @Override public boolean canPlaceItem(int slot,ItemStack stack){return !kind().equals("hub")&&slot>=0&&slot<items.size();}
    // Unowned automation must not bypass hub/claim permissions. No item-handler capability is exposed.
    @Override public int[] getSlotsForFace(Direction direction){return new int[0];}
    @Override public boolean canPlaceItemThroughFace(int slot,ItemStack stack,Direction direction){return false;}
    @Override public boolean canTakeItemThroughFace(int slot,ItemStack stack,Direction direction){return false;}
}
