package dev.anya.storage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
/** Main slots only. Exclude a backpack's host slot while transacting with its inner inventory. */
public final class MainInventory implements Container {
    public final ServerPlayer player;
    private final int excluded;
    public MainInventory(ServerPlayer p){this(p,-1);}
    public MainInventory(ServerPlayer p,int excluded){player=p;this.excluded=excluded;}
    public int getContainerSize(){return 36;}
    public boolean isEmpty(){for(int i=0;i<36;i++)if(!getItem(i).isEmpty())return false;return true;}
    public ItemStack getItem(int i){return i==excluded?ItemStack.EMPTY:player.getInventory().getItem(i);}
    public ItemStack removeItem(int i,int n){return i==excluded?ItemStack.EMPTY:player.getInventory().removeItem(i,n);}
    public ItemStack removeItemNoUpdate(int i){return i==excluded?ItemStack.EMPTY:player.getInventory().removeItemNoUpdate(i);}
    public void setItem(int i,ItemStack stack){if(i==excluded){if(!stack.isEmpty())throw new IllegalArgumentException("Backpack host slot is locked");return;}player.getInventory().setItem(i,stack);setChanged();}
    public void setChanged(){player.getInventory().setChanged();}
    public boolean stillValid(Player p){return p==player&&player.isAlive()&&!player.isSpectator();}
    public boolean canPlaceItem(int slot,ItemStack stack){return slot!=excluded&&slot>=0&&slot<36;}
    public void clearContent(){for(int i=0;i<36;i++)if(i!=excluded)setItem(i,ItemStack.EMPTY);}
}
