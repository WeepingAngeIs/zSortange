package dev.anya.storage.guide;

import dev.anya.storage.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.io.IOException;
import java.util.*;

public final class GuideService {
    public enum Grant { GIVEN, ALREADY, FULL, DISABLED }
    private static MinecraftServer server;
    private static GuideJournal journal;
    private static final Map<UUID,Long> openTimes=new HashMap<>();
    private static final Set<UUID> warned=new HashSet<>(),failed=new HashSet<>();
    private static GuideJournal journal(MinecraftServer s)throws IOException {
        if(s!=server){journal=new GuideJournal(s.getWorldPath(LevelResource.ROOT));server=s;}
        return journal;
    }
    public static boolean hasGuide(ServerPlayer p){for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(Content.GUIDE.get()))return true;return false;}
    public static Grant grantOnce(ServerPlayer p,GuideJournal store)throws IOException {
        if(!ExpansionRules.get().tutorialGrant)return Grant.DISABLED;
        var state=Profiles.of(p);
        if(state.guideGranted||store.claimed(p.getUUID())){
            if(!state.guideGranted){state.guideGranted=true;Profiles.changed(p);}return Grant.ALREADY;
        }
        if(hasGuide(p)){store.claim(p.getUUID());state.guideGranted=true;Profiles.changed(p);return Grant.ALREADY;}
        int slot=p.getInventory().getFreeSlot();if(slot<0)return Grant.FULL;
        if(!store.claim(p.getUUID())){state.guideGranted=true;Profiles.changed(p);return Grant.ALREADY;}
        state.guideGranted=true;Profiles.changed(p);
        p.getInventory().setItem(slot,new ItemStack(Content.GUIDE.get()));p.getInventory().setChanged();p.inventoryMenu.broadcastChanges();
        return Grant.GIVEN;
    }
    private static void grant(ServerPlayer p){
        if(!p.isAlive()||p.isSpectator()||p instanceof net.neoforged.neoforge.common.util.FakePlayer||Profiles.of(p).guideGranted)return;
        try{
            Grant result=grantOnce(p,journal(p.getServer()));
            if(result==Grant.GIVEN)AnyaStorage.tell(p,"Ancient Technology is in your inventory. Right-click it for the illustrated guide.");
            else if(result==Grant.FULL&&warned.add(p.getUUID()))AnyaStorage.tell(p,"Make one inventory slot free to receive Ancient Technology. It will not be dropped on the ground.");
        }catch(IOException e){failed.add(p.getUUID());if(warned.add(p.getUUID())){AnyaStorage.LOG.error("Automatic guide grant failed closed for {}",p.getUUID(),e);AnyaStorage.tell(p,"The guide grant could not be saved. Ask an operator to review the log; no extra book was created.");}}
    }
    public static void login(PlayerEvent.PlayerLoggedInEvent event){if(event.getEntity() instanceof ServerPlayer p)grant(p);}
    public static void tick(PlayerTickEvent.Post event){if(event.getEntity() instanceof ServerPlayer p&&p.tickCount%100==0&&ExpansionRules.get().tutorialGrant&&!Profiles.of(p).guideGranted&&!warned.contains(p.getUUID()))grant(p);}
    // A full inventory can become free without reconnecting. The reminder is rate-limited, not the retry.
    public static void inventoryRetry(PlayerTickEvent.Pre event){if(event.getEntity() instanceof ServerPlayer p&&p.tickCount%100==0&&warned.contains(p.getUUID())&&!failed.contains(p.getUUID())&&p.getInventory().getFreeSlot()>=0){warned.remove(p.getUUID());grant(p);}}
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event){openTimes.remove(event.getEntity().getUUID());warned.remove(event.getEntity().getUUID());failed.remove(event.getEntity().getUUID());}
    public static void open(ServerPlayer p){
        if(!p.isAlive()||p.isSpectator()||!hasGuide(p))return;
        long now=System.nanoTime();Long before=openTimes.put(p.getUUID(),now);if(before!=null&&now-before<750_000_000L)return;
        PacketDistributor.sendToPlayer(p,new GuidePacket(Config.get().revision,GuideContent.build(p.serverLevel())));
    }
    public static int recover(ServerPlayer p){
        if(!ExpansionRules.get().tutorialRecovery){AnyaStorage.tell(p,"Guide recovery is disabled by the operator.");return 0;}
        if(hasGuide(p)){open(p);return 1;}
        if(!p.isAlive()||p.isSpectator()||p.containerMenu!=p.inventoryMenu){AnyaStorage.tell(p,"Close other menus before recovering the guide.");return 0;}
        ItemStack plain=new ItemStack(Items.BOOK);int source=-1;
        for(int i=0;i<36;i++)if(ItemStack.isSameItemSameComponents(p.getInventory().getItem(i),plain)){source=i;break;}
        if(source<0){AnyaStorage.tell(p,"Carry one ordinary Book, then use /anya guide recover. No named or enchanted item is consumed.");return 0;}
        ItemStack old=p.getInventory().getItem(source);int destination=old.getCount()==1?source:p.getInventory().getFreeSlot();
        if(destination<0){AnyaStorage.tell(p,"Make one inventory slot free first.");return 0;}
        try{journal(p.getServer()).claim(p.getUUID());}catch(IOException e){AnyaStorage.LOG.error("Guide recovery claim failed",e);AnyaStorage.tell(p,"Recovery could not be recorded; your book was left unchanged.");return 0;}
        Profiles.of(p).guideGranted=true;Profiles.changed(p);old.shrink(1);p.getInventory().setItem(destination,new ItemStack(Content.GUIDE.get()));p.getInventory().setChanged();p.inventoryMenu.broadcastChanges();
        AnyaStorage.tell(p,"Recovered Ancient Technology using one ordinary Book.");return 1;
    }
    public static void stop(){server=null;journal=null;openTimes.clear();warned.clear();failed.clear();}
    private GuideService(){}
}
