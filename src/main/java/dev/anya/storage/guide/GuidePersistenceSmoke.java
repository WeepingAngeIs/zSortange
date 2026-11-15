package dev.anya.storage.guide;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import java.util.UUID;

/** Opt-in CI-only probe using two normal dedicated-server processes and the same disposable world. */
@EventBusSubscriber(modid=AnyaStorage.ID)
public final class GuidePersistenceSmoke {
    private static final UUID PLAYER=UUID.fromString("519ea9d2-c75b-44de-8978-8d1ea3a9b2a3");
    private static final UUID WORN=UUID.fromString("0a682ef4-a6f8-4f6c-8f03-af962101b9e7");
    @SubscribeEvent public static void started(ServerStartedEvent event)throws Exception {
        String phase=System.getProperty("anyastorage.guidePersistence","");
        if(phase.isEmpty())return;
        if(!phase.equals("seed")&&!phase.equals("verify"))throw new IllegalArgumentException("Unknown guide persistence probe phase");
        var server=event.getServer();
        var player=FakePlayerFactory.get(server.overworld(),new GameProfile(PLAYER,"GuidePersistence"));
        player.getInventory().clearContent();
        var journal=new GuideJournal(server.getWorldPath(LevelResource.ROOT));
        var state=Profiles.of(player);
        if(phase.equals("seed")){
            check(!state.guideGranted&&!journal.claimed(PLAYER),"Probe requires a fresh disposable world");
            state.protectedSlots=321;state.favorites.add("minecraft:diamond");state.wornBag=WORN;
            check(GuideService.grantOnce(player,journal)==GuideService.Grant.GIVEN,"First grant failed");
            check(GuideService.hasGuide(player),"First guide was not delivered");
            check(player.getInventory().getItem(0).getHoverName().getString().equals("Ancient Technology"),"Incorrect guide title");
            Profiles.changed(player);
        }else{
            check(state.guideGranted,"SavedData grant marker did not survive process restart");
            check(journal.claimed(PLAYER),"Durable grant file did not survive process restart");
            check(state.protectedSlots==321&&state.favorites.contains("minecraft:diamond")&&WORN.equals(state.wornBag),"Existing player preferences changed");
            check(GuideService.grantOnce(player,journal)==GuideService.Grant.ALREADY,"Restart regranted the book");
            check(player.getInventory().isEmpty(),"A lost guide was duplicated on restart");
            // Exercise the durable file alone, as when player SavedData lags behind a crash.
            state.guideGranted=false;Profiles.changed(player);
            check(GuideService.grantOnce(player,journal)==GuideService.Grant.ALREADY,"Journal failed to reject a stale SavedData grant");
            check(state.guideGranted&&player.getInventory().isEmpty(),"Journal recovery failed");
        }
        server.saveEverything(true,true,true);
        AnyaStorage.LOG.info("ANYA_GUIDE_PERSISTENCE_{}: real dedicated server, durable journal and existing preferences verified",phase.toUpperCase(java.util.Locale.ROOT));
        server.halt(false);
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private GuidePersistenceSmoke(){}
}
