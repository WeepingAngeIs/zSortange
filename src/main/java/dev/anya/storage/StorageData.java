package dev.anya.storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** Backpack contents have one world-owned identity, including after a creative item clone. */
public final class StorageData extends SavedData {
    public final Map<UUID,Bag> bags=new LinkedHashMap<>();
    public final Map<UUID,Prefs> players=new HashMap<>();
    public final Set<UUID> rewardedDragons=new HashSet<>();
    public static StorageData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(StorageData::new,StorageData::load,null),"anya_storage");
    }
    public final class Bag {
        public String tier;
        public final StorageInventory inventory, upgrades;
        public final Set<String> disabledUpgrades=new HashSet<>(), filters=new TreeSet<>();
        public Bag(String tier,int size) {
            this.tier=tier;inventory=new StorageInventory(size,true,StorageData.this::setDirty);upgrades=new StorageInventory(8,false,StorageData.this::setDirty);
        }
        public boolean hasUpgrade(String id) {
            if(!Rules.current.upgrades||!Boolean.TRUE.equals(Rules.current.upgradeTypes.get(id))||disabledUpgrades.contains(id))return false;
            var item=AnyaStorage.UPGRADES.get(id);if(item==null)return false;
            for(int i=0;i<upgrades.getContainerSize();i++)if(upgrades.getItem(i).is(item.get()))return true;return false;
        }
    }
    public static final class Prefs {
        public BitSet locked=new BitSet(36);
        public Set<String> favorites=new TreeSet<>();
        public boolean autoStow=true,autoFeed=true,autoRefill=true,autoCompact=true;
        public transient Map<Integer,String> skipped=new LinkedHashMap<>();
        public transient long lastAction=-1000;
        public Prefs(){locked.set(0,9);}
    }
    public Prefs prefs(UUID id){return players.computeIfAbsent(id,k->{setDirty();return new Prefs();});}
    public static CompoundTag metadata(ItemStack stack){return stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();}
    public static void metadata(ItemStack stack,CompoundTag tag){stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));}
    public static String itemTier(ItemStack stack){String id=metadata(stack).getString("AnyaTier");return id.isEmpty()?"starter":id;}
    public static UUID bagId(ItemStack stack){CompoundTag tag=metadata(stack);return tag.hasUUID("AnyaBag")?tag.getUUID("AnyaBag"):null;}
    public Bag bag(ItemStack stack) {
        if(!(stack.getItem() instanceof BackpackItem))throw new IllegalArgumentException("Not a backpack");
        CompoundTag tag=metadata(stack);UUID id=bagId(stack);String tierId=itemTier(stack);Rules.Tier tier=Rules.current.tier(tierId);
        if(id==null){id=UUID.randomUUID();tag.putUUID("AnyaBag",id);metadata(stack,tag);}
        Bag bag=bags.get(id);
        if(bag==null){if(tier==null)throw new IllegalArgumentException("Unknown backpack tier");bag=new Bag(tier.id,tier.slots);bags.put(id,bag);setDirty();}
        if(tier!=null && tier.slots>=bag.inventory.getContainerSize()) {
            if(!bag.tier.equals(tier.id)||tier.slots>bag.inventory.getContainerSize()){bag.tier=tier.id;bag.inventory.expand(tier.slots);setDirty();}
        }
        return bag;
    }
    public void validateTierChange(Rules.Config next) {
        for(Bag bag:bags.values()) {
            Rules.Tier t=next.tier(bag.tier);
            if(t==null)throw new IllegalArgumentException("Cannot delete a tier used by an existing backpack: "+bag.tier);
            for(int i=t.slots;i<bag.inventory.getContainerSize();i++)if(!bag.inventory.getItem(i).isEmpty())throw new IllegalArgumentException("Tier "+t.name+" has occupied slots beyond the proposed capacity");
            for(int i=t.upgrades;i<bag.upgrades.getContainerSize();i++)if(!bag.upgrades.getItem(i).isEmpty())throw new IllegalArgumentException("Tier "+t.name+" has installed upgrades beyond the proposed limit");
        }
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider lookup) {
        tag.putInt("Schema",1);ListTag bagList=new ListTag();
        bags.forEach((id,b)->{CompoundTag e=new CompoundTag();e.putUUID("Id",id);e.putString("Tier",b.tier);e.put("Inventory",b.inventory.save(lookup));e.put("Upgrades",b.upgrades.save(lookup));e.put("Disabled",strings(b.disabledUpgrades));e.put("Filters",strings(b.filters));bagList.add(e);});
        tag.put("Bags",bagList);ListTag prefsList=new ListTag();
        players.forEach((id,p)->{CompoundTag e=new CompoundTag();e.putUUID("Id",id);e.putLongArray("Locked",p.locked.toLongArray());e.put("Favorites",strings(p.favorites));e.putBoolean("AutoStow",p.autoStow);e.putBoolean("AutoFeed",p.autoFeed);e.putBoolean("AutoRefill",p.autoRefill);e.putBoolean("AutoCompact",p.autoCompact);prefsList.add(e);});
        tag.put("Players",prefsList);tag.put("Dragons",strings(rewardedDragons.stream().map(UUID::toString).toList()));return tag;
    }
    public static StorageData load(CompoundTag tag,HolderLookup.Provider lookup) {
        if(tag.getInt("Schema")!=1)throw new IllegalStateException("Unsupported Anya Storage world-data schema");
        StorageData d=new StorageData();ListTag bags=tag.getList("Bags",Tag.TAG_COMPOUND);
        for(int i=0;i<bags.size();i++){CompoundTag e=bags.getCompound(i);UUID id=e.getUUID("Id");if(d.bags.containsKey(id))throw new IllegalStateException("Duplicate backpack UUID in world data");Bag b=d.new Bag(e.getString("Tier"),0);b.inventory.load(e.getCompound("Inventory"),lookup);b.upgrades.load(e.getCompound("Upgrades"),lookup);b.disabledUpgrades.addAll(readStrings(e,"Disabled"));b.filters.addAll(readStrings(e,"Filters"));d.bags.put(id,b);}
        ListTag ps=tag.getList("Players",Tag.TAG_COMPOUND);
        for(int i=0;i<ps.size();i++){CompoundTag e=ps.getCompound(i);Prefs p=new Prefs();p.locked=BitSet.valueOf(e.getLongArray("Locked"));p.favorites.addAll(readStrings(e,"Favorites"));p.autoStow=e.getBoolean("AutoStow");p.autoFeed=e.getBoolean("AutoFeed");p.autoRefill=e.getBoolean("AutoRefill");p.autoCompact=e.getBoolean("AutoCompact");d.players.put(e.getUUID("Id"),p);}
        for(String id:readStrings(tag,"Dragons"))d.rewardedDragons.add(UUID.fromString(id));return d;
    }
    public static ListTag strings(Collection<String> values){ListTag list=new ListTag();values.forEach(v->list.add(StringTag.valueOf(v)));return list;}
    public static List<String> readStrings(CompoundTag tag,String key){ListTag list=tag.getList(key,Tag.TAG_STRING);List<String> out=new ArrayList<>();for(int i=0;i<list.size();i++)out.add(list.getString(i));return out;}
}
