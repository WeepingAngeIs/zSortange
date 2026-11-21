package dev.anya.storage.visual;

import dev.anya.storage.Client;
import dev.anya.storage.ui.StorageGrid.Box;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.world.item.Items;

/** Loaded only by the EMI client. No EMI symbols are linked by the plain-client fixture. */
final class EmiProbe {
    static boolean ready() {
        return !EmiApi.getIndexStacks().isEmpty()
                && !EmiApi.getRecipeManager().getRecipesByOutput(EmiStack.of(Items.CRAFTING_TABLE)).isEmpty();
    }
    static void search() {
        EmiApi.setSearchText("crafting table");
    }
    private static boolean table(int x,int y) {
        var hit=EmiApi.getHoveredStack(x,y,false);
        return hit.isClickable() && hit.getStack().getEmiStacks().stream().anyMatch(s->s.getItemStack().is(Items.CRAFTING_TABLE));
    }
    static void clearOfSidebar(Box b) {
        if(b.width()==0 || b.height()==0)return;
        for(int x=b.x()+3;x<b.x()+b.width();x+=8)for(int y=b.y()+3;y<b.y()+b.height();y+=8){
            var hit=EmiApi.getHoveredStack(x,y,false);
            if(!hit.isEmpty() && hit.isClickable())throw new IllegalStateException("EMI item under chest control at "+x+","+y);
        }
    }
    static Box findSidebar(Client.Screen screen) {
        var main=screen.emiMainBounds();
        var extra=screen.emiExtraBounds();
        for(int y=8;y<screen.height-8;y+=6)for(int x=8;x<screen.width-8;x+=6){
            if(main.contains(x,y) || !table(x,y))continue;
            boolean clear=true;
            for(var b:extra)if(b.contains(x,y))clear=false;
            if(clear)return new Box(x,y,1,1);
        }
        return null;
    }
    static boolean hovered() {
        var hit=EmiApi.getHoveredStack(false);
        return hit.isClickable() && hit.getStack().getEmiStacks().stream().anyMatch(s->s.getItemStack().is(Items.CRAFTING_TABLE));
    }
    private EmiProbe(){}
}
