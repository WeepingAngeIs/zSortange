package dev.anya.storage;
import com.mojang.serialization.*;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.neoforged.neoforge.common.conditions.ICondition;
public record RecipeCondition(String group) implements ICondition {
    public static final MapCodec<RecipeCondition> CODEC=RecordCodecBuilder.mapCodec(i->i.group(Codec.STRING.fieldOf("group").forGetter(RecipeCondition::group)).apply(i,RecipeCondition::new));
    public boolean test(IContext context) {
        Config.Data d=Config.get();
        if(group.startsWith("upgrade."))return Boolean.TRUE.equals(d.recipes.get("upgrades"))&&Boolean.TRUE.equals(d.upgrades.get(group.substring(8)));
        if(!Boolean.TRUE.equals(d.recipes.get(group)))return false;
        return switch(group){case "backpacks"->d.backpacks;case "hubs","terminals"->d.network;default->true;};
    }
    public MapCodec<? extends ICondition> codec(){return CODEC;}
}
