package dev.anya.storage.appearance;

import dev.anya.storage.*;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** The look is independent of Tier, Capacity, UpgradeSlots and BagId. No inventory data is public. */
public final class Looks {
    public static final List<String> ELEMENTS=List.of("plain","leaf","stone","diamond","magma","water");
    public static final List<String> SKINS=List.of("elemental","bow_cat","midnight_cat","spooky","cute_spooky","futuristic","galaxy","oak","cherry","dark_oak");
    public static final List<String> INTERIORS=List.of("atelier","botanical","midnight","galaxy","circuit","ocean");
    public static final List<String> LEAVES=List.of("oak","spruce","birch","jungle","acacia","dark_oak","mangrove","cherry");
    public record Look(String element,String skin,String leaves,String interior){
        public String texture(){return skin.equals("elemental")?(element.equals("leaf")?"leaf_"+leaves:element):skin;}
    }
    public static Look of(ItemStack stack){
        CompoundTag t=BagInventory.data(stack);
        String element=known(ELEMENTS,t.getString("Element"),"plain"),skin=known(SKINS,t.getString("Skin"),"elemental");
        String design=skin.equals("elemental")?element:skin;
        String automatic=switch(design){case "leaf","oak","cherry","dark_oak"->"botanical";case "water","diamond"->"ocean";case "galaxy"->"galaxy";case "futuristic","stone"->"circuit";case "magma","spooky","cute_spooky","midnight_cat"->"midnight";default->"atelier";};
        return new Look(element,skin,known(LEAVES,t.getString("LeafSpecies"),"oak"),known(INTERIORS,t.getString("Interior"),automatic));
    }
    public static String known(List<String> list,String key,String fallback){return list.contains(key)?key:fallback;}
    public static boolean unlocked(ItemStack bag,String key,boolean element){
        String current=element?of(bag).element:of(bag).skin;
        if(key.equals(element?"plain":"elemental")||key.equals(current))return true;
        ListTag list=BagInventory.data(bag).getList(element?"Elements":"Skins",Tag.TAG_STRING);
        for(Tag t:list)if(t.getAsString().equals(key))return true;return false;
    }
    public static void apply(ItemStack bag,String key,boolean element){
        if(!(element?ELEMENTS:SKINS).contains(key))throw new IllegalArgumentException("Unknown appearance");
        CompoundTag t=BagInventory.data(bag);String listKey=element?"Elements":"Skins",field=element?"Element":"Skin";
        ListTag list=t.getList(listKey,Tag.TAG_STRING);Set<String> values=new LinkedHashSet<>();values.add(element?of(bag).element:of(bag).skin);
        for(Tag v:list)if((element?ELEMENTS:SKINS).contains(v.getAsString()))values.add(v.getAsString());values.add(key);
        ListTag out=new ListTag();values.forEach(s->out.add(StringTag.valueOf(s)));t.put(listKey,out);t.putString(field,key);BagInventory.data(bag,t);
    }
    public static ItemStack styledCopy(ItemStack bag,String key,boolean element){ItemStack result=bag.copyWithCount(1);BagInventory.initialize(result);apply(result,key,element);return result;}
    public static String title(String s){StringBuilder out=new StringBuilder();for(String part:s.split("_")){if(!out.isEmpty())out.append(' ');out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));}return out.toString();}
    private Looks(){}
}
