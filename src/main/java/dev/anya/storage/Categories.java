package dev.anya.storage;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.*;
import java.util.*;

public final class Categories {
    public static String id(ItemStack s) { return BuiltInRegistries.ITEM.getKey(s.getItem()).toString(); }
    public static String of(ItemStack s) {
        String direct=Config.get().categoryRules.get(id(s));if(direct!=null)return direct;
        for(var rule:Config.get().categoryRules.entrySet())if(rule.getKey().startsWith("#")&&s.getTags().anyMatch(tag->tag.location().toString().equals(rule.getKey().substring(1))))return rule.getValue();
        String path=BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        if(s.has(DataComponents.FOOD))return "food";
        if(path.endsWith("_ore")||path.startsWith("raw_")||tag(s,"ores")||tag(s,"raw_materials"))return "ores";
        if(path.endsWith("_ingot")||path.endsWith("_nugget")||tag(s,"gems")||Set.of("diamond","emerald","lapis_lazuli","quartz","amethyst_shard").contains(path))return "materials";
        Item item=s.getItem();
        if(item instanceof ArmorItem||item instanceof DiggerItem||item instanceof SwordItem||item instanceof BowItem||item instanceof CrossbowItem||item instanceof TridentItem||item instanceof FishingRodItem)return "equipment";
        if(path.contains("redstone")||Set.of("repeater","comparator","piston","sticky_piston","observer","dispenser","dropper","hopper").contains(path))return "redstone";
        if(path.contains("seed")||Set.of("wheat","sugar_cane","bamboo","cactus","kelp","bone_meal").contains(path))return "farming";
        if(Set.of("bone","rotten_flesh","string","spider_eye","gunpowder","ender_pearl","blaze_rod","slime_ball","magma_cream","ghast_tear","leather","feather").contains(path))return "mob_drops";
        if(item instanceof BlockItem)return "blocks";
        return "misc";
    }
    private static boolean tag(ItemStack s,String name) { return s.getTags().anyMatch(t->t.location().getPath().equals(name)||t.location().getPath().startsWith(name+"/")); }
    public static boolean matches(ItemStack stack,String search) {
        if(search.isBlank())return true;
        String text=search.toLowerCase(Locale.ROOT);
        if(text.startsWith("@"))return BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().contains(text.substring(1));
        if(text.startsWith("#"))return stack.getTags().anyMatch(t->t.location().toString().contains(text.substring(1)));
        return id(stack).contains(text)||stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(text)||of(stack).contains(text);
    }
    public static Comparator<ItemStack> order() { return Comparator.comparing(Categories::of).thenComparing(Categories::id).thenComparing(s->s.getComponents().toString()); }
    private Categories() {}
}
