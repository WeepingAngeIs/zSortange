package dev.anya.storage;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
public final class StorageBlockEntity extends BlockEntity {
    public UUID identity=UUID.randomUUID(),owner;
    public final Set<UUID> trusted=new HashSet<>();
    public String name="Anya Base";
    public int earnedRadius=Rules.current.baseRadius,progress,label,viewers;
    public boolean overflow=true;
    public final Map<Long,String> destinations=new HashMap<>();
    public final StorageInventory inventory;
    public float open,previousOpen;
    public StorageBlockEntity(BlockPos pos,BlockState state){super(AnyaStorage.STORAGE_ENTITY.get(),pos,state);inventory=new StorageInventory(kind()==0?0:kind()==2?Rules.current.cubeSlots:Rules.current.crateSlots,false,this::setChanged);}
    public int kind(){return getBlockState().getBlock() instanceof StorageBlock b?b.kind:0;}
    public int radius(){return Math.min(Rules.current.maxRadius,Math.max(Rules.current.baseRadius,earnedRadius));}
    public int nextRadius(){return Math.min(Rules.current.maxRadius,radius()+Rules.current.radiusStep);}
    public int nextCost(){return Math.multiplyExact(Rules.current.radiusCost,1+Math.max(0,(radius()-Rules.current.baseRadius)/Rules.current.radiusStep));}
    public boolean inRange(BlockPos p){return Math.abs((long)p.getX()-worldPosition.getX())<=radius()&&Math.abs((long)p.getY()-worldPosition.getY())<=radius()&&Math.abs((long)p.getZ()-worldPosition.getZ())<=radius();}
    public boolean allowed(ServerPlayer p){return owner==null||owner.equals(p.getUUID())||trusted.contains(p.getUUID())||p.hasPermissions(2);}
    public boolean canManage(ServerPlayer p){return owner==null||owner.equals(p.getUUID())||p.hasPermissions(2);}
    public void changed(){setChanged();if(level!=null&&!level.isClientSide)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);}
    public void viewing(int delta){viewers=Math.max(0,viewers+delta);if(level!=null)level.blockEvent(worldPosition,getBlockState().getBlock(),1,viewers);}
    @Override public boolean triggerEvent(int id,int value){if(id==1){viewers=value;return true;}return super.triggerEvent(id,value);}
    @Override public void onLoad(){super.onLoad();if(level instanceof ServerLevel)StorageOps.register(this);}
    @Override public void setRemoved(){StorageOps.unregister(this);super.setRemoved();}
    public static void tick(Level level,BlockPos pos,BlockState state,StorageBlockEntity b){if(level.isClientSide){b.previousOpen=b.open;b.open=Math.max(0,Math.min(1,b.open+(b.viewers>0?0.1f:-0.1f)));}}
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider lookup){
        super.saveAdditional(tag,lookup);tag.putUUID("Identity",identity);if(owner!=null)tag.putUUID("Owner",owner);tag.putString("Name",name);tag.putInt("Radius",earnedRadius);tag.putInt("Progress",progress);tag.putInt("Label",label);tag.putBoolean("Overflow",overflow);
        tag.put("Inventory",inventory.save(lookup));tag.put("Trusted",StorageData.strings(trusted.stream().map(UUID::toString).toList()));
        ListTag ds=new ListTag();destinations.forEach((p,c)->{CompoundTag d=new CompoundTag();d.putLong("Pos",p);d.putString("Category",c);ds.add(d);});tag.put("Destinations",ds);
    }
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider lookup){
        super.loadAdditional(tag,lookup);if(tag.hasUUID("Identity"))identity=tag.getUUID("Identity");owner=tag.hasUUID("Owner")?tag.getUUID("Owner"):null;name=tag.contains("Name")?tag.getString("Name"):"Anya Base";
        earnedRadius=Math.max(4,Math.min(128,tag.getInt("Radius")));progress=Math.max(0,tag.getInt("Progress"));label=Math.floorMod(tag.getInt("Label"),7);overflow=!tag.contains("Overflow")||tag.getBoolean("Overflow");
        if(tag.contains("Inventory"))inventory.load(tag.getCompound("Inventory"),lookup);trusted.clear();for(String id:StorageData.readStrings(tag,"Trusted"))trusted.add(UUID.fromString(id));
        destinations.clear();ListTag ds=tag.getList("Destinations",Tag.TAG_COMPOUND);for(int i=0;i<ds.size();i++){CompoundTag d=ds.getCompound(i);String c=d.getString("Category");if(Rules.CATEGORIES.contains(c))destinations.put(d.getLong("Pos"),c);}
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider lookup){CompoundTag t=new CompoundTag();t.putInt("Label",label);t.putInt("Viewers",viewers);return t;}
    @Override public void handleUpdateTag(CompoundTag tag,HolderLookup.Provider lookup){label=tag.getInt("Label");viewers=tag.getInt("Viewers");}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
}
