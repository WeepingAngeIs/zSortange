package dev.anya.storage;

import dev.anya.storage.appearance.Looks;
import dev.anya.storage.appearance.StyleRecipe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;

/** Computes one direct upgrade for the physical bag; the caller owns the world/hand transaction. */
public final class BackpackAttachment {
    public record Result(boolean recognized,ItemStack output,int cost,List<ItemStack> remainders,ItemStack refund,String message) {
        private static Result denied(String reason){return new Result(true,ItemStack.EMPTY,0,List.of(),ItemStack.EMPTY,reason);}
    }
    private static final Result PASS=new Result(false,ItemStack.EMPTY,0,List.of(),ItemStack.EMPTY,"");

    public static Result preview(ServerPlayer player,ItemStack bag,ItemStack hand){
        if(!BackpackAccess.isBag(bag) || hand.isEmpty())return PASS;
        for(var upgrade:Content.UPGRADES.entrySet()){
            if(!hand.is(upgrade.getValue().get()))continue;
            String id=upgrade.getKey();
            ItemStack output=bag.copy();BagInventory.initialize(output);
            CompoundTag data=BagInventory.data(output);
            var installed=Upgrades.installedItems(output);
            String reason=Upgrades.discProblem(installed,id);
            if(!reason.isEmpty())return Result.denied(reason);
            int slots=data.getInt("UpgradeSlots");
            if(slots<=0 || installed.size()>slots)return Result.denied("Cannot replace a disc in this backpack.");
            ItemStack refund=ItemStack.EMPTY;
            if(installed.size()==slots){
                var first=installed.getCompound(0);
                String oldest=first.getString("Id");
                var expected=Content.UPGRADES.get(oldest);
                refund=ItemStack.parseOptional(player.registryAccess(),first.getCompound("Stack"));
                if(expected==null || refund.getCount()!=1 || !refund.is(expected.get()))
                    return Result.denied("The saved disc could not be recovered.");
                installed.remove(0);
                BackpackSmelter.removedDisc(data,oldest);
            }
            Upgrades.addDisc(installed,id,hand.copyWithCount(1),player);
            data.put("InstalledUpgrades",installed);BagInventory.data(output,data);
            return new Result(true,output,1,List.of(),refund,swapMessage(refund,hand));
        }
        for(var style:Content.STYLES.entrySet()){
            if(!hand.is(style.getValue().get()))continue;
            boolean element=style.getKey().startsWith("element_");
            String key=style.getKey().substring(element?8:5);
            var look=Looks.of(bag);
            if(key.equals(element?look.element():look.skin()))return Result.denied("Already wearing this style");
            Result styleResult=craft(player,bag,hand,1,"backpack_style",StyleRecipe.class);
            if(styleResult.output().isEmpty())return styleResult;
            var data=BagInventory.data(styleResult.output());
            var receipts=data.getCompound("AttachedStyleItems");
            String previous=element?"element_"+look.element():"skin_"+look.skin();
            if(receipts.contains(style.getKey()))return Result.denied("This style is already attached. Select it in the Wardrobe.");
            ItemStack refund=ItemStack.EMPTY;
            if(receipts.contains(previous)){
                refund=ItemStack.parseOptional(player.registryAccess(),receipts.getCompound(previous));
                var expected=Content.STYLES.get(previous);
                if(expected==null || refund.getCount()!=1 || !refund.is(expected.get()))
                    return Result.denied("The saved style item could not be recovered.");
                receipts.remove(previous);
            }
            if(!player.getAbilities().instabuild)receipts.put(style.getKey(),hand.copyWithCount(1).save(player.registryAccess()));
            data.put("AttachedStyleItems",receipts);
            BagInventory.data(styleResult.output(),data);
            return new Result(true,styleResult.output(),1,styleResult.remainders(),refund,swapMessage(refund,hand));
        }
        var config=Config.get();
        String material=Categories.id(hand);
        String current=BagInventory.data(bag).getString("Tier");
        if(current.isBlank())current="starter";
        Config.Tier chosen=null;
        boolean known=false;
        for(var tier:config.tiers){
            if(tier.id.equals("starter") || !material.equals(tier.material))continue;
            known=true;
            if(!current.equals(Config.predecessor(config,tier)) || !Config.unavailable(config,tier).isEmpty())continue;
            if(chosen!=null)return Result.denied("Several tiers use this material. Choose the recipe in a crafting table.");
            chosen=tier;
        }
        if(chosen==null)return known?Result.denied("This material cannot upgrade the current tier."):PASS;
        if(hand.getCount()<chosen.cost)return Result.denied("Hold "+chosen.cost+" "+hand.getHoverName().getString()+" to upgrade to "+chosen.name);
        return craft(player,bag,hand,chosen.cost,"backpack_upgrade",UpgradeRecipe.class);
    }

    private static String swapMessage(ItemStack previous,ItemStack next){
        return previous.isEmpty()?next.getHoverName().getString()+" installed"
                :"Replaced "+previous.getHoverName().getString()+" with "+next.getHoverName().getString();
    }

    private static Result craft(ServerPlayer p,ItemStack bag,ItemStack held,int cost,String id,Class<? extends CraftingRecipe> type){
        var level=p.serverLevel();
        var holder=level.getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath("anyastorage",id));
        if(holder.isEmpty() || !type.isInstance(holder.get().value()))return Result.denied("This direct upgrade recipe is unavailable.");
        var recipe=type.cast(holder.get().value());
        var cells=new ArrayList<>(Collections.nCopies(9,ItemStack.EMPTY));cells.set(0,bag.copy());
        for(int i=1;i<=cost;i++)cells.set(i,held.copyWithCount(1));
        var input=CraftingInput.of(3,3,cells);
        if(!recipe.matches(input,level))return Result.denied("This upgrade is unavailable under the current rules.");
        var output=recipe.assemble(input,p.registryAccess());
        if(!BackpackAccess.isBag(output))return Result.denied("The recipe did not return a backpack.");
        return new Result(true,output,cost,recipe.getRemainingItems(input).stream().filter(s->!s.isEmpty()).toList(),ItemStack.EMPTY,Looks.backpackName(output));
    }
    private BackpackAttachment(){}
}
