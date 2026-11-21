package dev.anya.smelterreview;

import dev.anya.storage.Content;
import dev.anya.storage.Packets;
import dev.anya.storage.StoreMenu;
import dev.anya.storage.Transactions;
import dev.anya.storage.Upgrades;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import static dev.anya.smelterreview.SmelterGameTests.*;

/** Issue one ordinary session-checked menu action per real server tick. */
final class SpecialistMenuClicks {
    private record Button(String id,int install,int filter,String rule,String key){}
    private static final List<Button> BUTTONS=List.of(
            new Button("manual_smoking",31,33,"minecraft:beef","SmokeFilter"),
            new Button("auto_smoking",32,33,"minecraft:porkchop","SmokeFilter"),
            new Button("manual_blasting",40,42,"minecraft:raw_iron","BlastFilter"),
            new Button("auto_blasting",41,42,"minecraft:raw_copper","BlastFilter"));
    private final ServerPlayer player;
    private int index,step;

    SpecialistMenuClicks(ServerLevel level){
        player=menuTester(level,"DiscMenu");
        player.getInventory().setItem(0,new ItemStack(Content.BACKPACK.get()));
        StoreMenu.bag(player,bag(player),true);
    }
    private StoreMenu menu(){return (StoreMenu)player.containerMenu;}
    private void act(String verb,String text,int value){
        var menu=menu();
        menu.action(new Packets.Action(menu.containerId,menu.session(),menu.revision(),verb,text,value));
    }
    boolean tick(){
        var b=BUTTONS.get(index);var module=named(disc(b.id),"Menu disc "+b.id);
        switch(step){
            case 0->{
                check(menu().getSlot(b.install).getItem().is(module.getItem()),"Install button does not show actual disc "+b.id);
                player.getInventory().setItem(9,module.copy());
                act("slot","",b.install);
                var saved=Upgrades.installedItems(bag(player).stack);
                check(saved.size()==1 && saved.getCompound(0).getString("Id").equals(b.id) &&
                        Transactions.same(module,ItemStack.parseOptional(player.registryAccess(),saved.getCompound(0).getCompound("Stack"))) &&
                        player.getInventory().countItem(module.getItem())==0,"Left-click failed to install exactly one named "+b.id);
                var icon=menu().getSlot(b.install).getItem();
                check(icon.is(module.getItem()) && icon.getOrDefault(DataComponents.ENCHANTMENT_GLINT_OVERRIDE,false),"Missing equipped disc icon "+b.id);
            }
            case 1->act("text",b.rule,0);
            case 2,3->{
                act("slot","",b.filter);
                var rules=data(player).getList(b.key,Tag.TAG_STRING);
                boolean added=step==2;
                check(added?rules.size()==1 && rules.getString(0).equals(b.rule):rules.isEmpty(),
                        "Filter button "+b.filter+" did not "+(added?"add ":"remove ")+b.rule);
                for(String key:List.of("SmeltFilter","SmokeFilter","BlastFilter"))
                    if(!key.equals(b.key))check(data(player).getList(key,Tag.TAG_STRING).isEmpty(),"Filter input changed "+key);
            }
            case 4->{
                act("slot","",b.install | (1<<8));
                check(!Upgrades.installed(bag(player),b.id) && player.getInventory().countItem(module.getItem())==1 &&
                        returned(player,module),"Right-click failed to return exactly the named "+b.id);
                step=0;
                if(++index==BUTTONS.size()){player.closeContainer();return true;}
                return false;
            }
        }
        step++;
        return false;
    }
}
