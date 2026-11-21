package dev.anya.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public class AccessCheck {
    private static final InteractionHand HAND = InteractionHand.MAIN_HAND;
    private static final BlockPos POS = new BlockPos(0,64,0);
    private static final UUID HUB = UUID.fromString("81a13ef7-ed41-4d09-b06b-52d524f09a61");

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static ItemStack tablet(boolean powered) {
        ItemStack stack = new ItemStack(powered ? Content.CREATIVE_TABLET.get() : Content.CUPID_TABLET.get());
        CompoundTag tag = new CompoundTag();
        tag.putLong("HubPos", POS.asLong());
        tag.putString("HubDimension", "minecraft:overworld");
        BagInventory.data(stack,tag);
        return stack;
    }
    private static CreativeTabletAccess.Session open(ServerPlayer player, ItemStack stack) {
        player.main = stack;
        Content.CREATIVE_TABLET.get().use(player.world,player,HAND);
        var session = CreativeTabletAccess.session(player);
        require(session!=null && session.held() && session.menu!=null && session.menu==player.containerMenu,
                "Signed UwU Tablet did not open a held session");
        require(CreativeTabletAccess.ticketCount()==1 && player.server.home.chunks.tickets.size()==1,
                "Expected one live home ticket");
        return session;
    }
    private static void closed(ServerPlayer p, String reason) {
        require(p.containerMenu==p.inventoryMenu && CreativeTabletAccess.session(p)==null
                && CreativeTabletAccess.ticketCount()==0 && p.server.home.chunks.tickets.isEmpty(), reason);
    }
    private static void deny(ServerPlayer p, ItemStack stack, String reason) {
        p.main=stack;
        CreativeTabletAccess.open(p,HAND);
        closed(p,reason);
    }
    private static void finish(ServerPlayer p, CreativeTabletAccess.Session s) {
        CreativeTabletAccess.finish(p,s.menu);
        p.closeContainer();
        closed(p,"Closing the menu leaked its home ticket");
    }
    public static void main(String[] args) throws Exception {
        Path jar=Path.of(args[0]).toRealPath();
        for (var c : List.of(CreativeTabletAccess.class,CreativeTabletAccess.Session.class,
                             StorageItem.class,Network.class,BasePermissions.class)) {
            var origin=Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            require(origin.equals(jar),"Unexpected code source for "+c.getName());
        }
        var server=new MinecraftServer();
        var owner=new ServerPlayer(server);
        var member=new ServerPlayer(server);
        var stranger=new ServerPlayer(server);
        var away=new ServerLevel("minecraft:the_nether");
        var hub=new StorageEntity(server.home,POS);
        hub.identity=HUB;
        hub.owner=owner.id;
        hub.members.put(member.id,BasePermissions.VIEW);

        // Prior releases used this saved world key and signed these four UTF-8 lines.
        byte[] key=new byte[32];
        for(int i=0;i<key.length;i++)key[i]=(byte)i;
        var keyTag=new CompoundTag();keyTag.putByteArray("Key",key);
        server.home.data.saved.put("anya_creative_tablet_key",keyTag);
        var legacy=tablet(true);
        var tag=BagInventory.data(legacy);
        tag.putUUID("HubId",HUB);
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
        tag.putByteArray("CreativeSeal",mac.doFinal(("minecraft:overworld\n64\n"+HUB+"\nnull").getBytes(StandardCharsets.UTF_8)));
        BagInventory.data(legacy,tag);
        require(CreativeTabletAccess.priorHubBinding(owner,legacy,hub),"Previous signed item/world format rejected");
        var fresh=tablet(true);
        owner.main=fresh;
        CreativeTabletAccess.bind(owner,fresh,hub);
        require(CreativeTabletAccess.priorHubBinding(owner,fresh,hub),"Local Survival binding did not issue a valid signature");

        var cupid=tablet(false);
        owner.main=cupid;
        Content.CUPID_TABLET.get().use(owner.world,owner,HAND);
        require(StorageItem.bound(owner,cupid)==hub,"Standard Cupid Tablet did not resolve nearby authorized hub");
        require(StoreMenu.cupidOpens==1,"Cupid did not reach the ordinary menu");
        owner.x=3000;
        Content.CUPID_TABLET.get().use(owner.world,owner,HAND);
        require(StorageItem.bound(owner,cupid)==null,"Normal Cupid Tablet exceeded its hub radius");
        require(StoreMenu.cupidOpens==1,"Out-of-range Cupid opened storage");
        var s=open(owner,legacy);
        require(Network.guard(owner,hub,List.of(),BasePermissions.ALL,s).getAsBoolean(),"Owner rights at long distance");
        owner.mode=GameType.CREATIVE;
        require(s.held() && s.menu.stillValid(owner),"Entering Creative interrupted legitimate access");
        owner.mode=GameType.SURVIVAL;
        require(s.held() && s.menu.stillValid(owner),"Returning to Survival interrupted legitimate access");
        owner.mode=GameType.SPECTATOR;
        require(!s.held(),"Spectator retained a held session");
        var tick=new ServerTickEvent.Post(server);
        CreativeTabletAccess.tick(tick);
        closed(owner,"Spectator transition leaked session or ticket");
        owner.mode=GameType.SURVIVAL;
        owner.x=.5; owner.world=away;
        require(StorageItem.bound(owner,cupid)==null,"Normal Cupid Tablet crossed dimensions");
        finish(owner,open(owner,fresh));
        deny(owner,cupid,"Normal Cupid Tablet entered UwU routing");
        var unsigned=tablet(true);
        deny(owner,unsigned,"Unsigned UwU Tablet opened remotely");
        CreativeTabletAccess.bind(owner,unsigned,hub);
        require(!CreativeTabletAccess.priorHubBinding(owner,unsigned,hub),"Remote player signed an item without being present");
        var tampered=tablet(true);
        var forged=BagInventory.data(legacy);forged.putLong("HubPos",100L);BagInventory.data(tampered,forged);
        deny(owner,tampered,"Changing the signed home position retained access");

        stranger.world=away;
        deny(stranger,legacy,"Possession granted untrusted player access");
        member.world=away;
        var shared=open(member,legacy);
        var canView=Network.guard(member,hub,List.of(),BasePermissions.VIEW,shared);
        var canTake=Network.guard(member,hub,List.of(),BasePermissions.WITHDRAW,shared);
        require(canView.getAsBoolean() && !canTake.getAsBoolean(),"Member's own view-only grant was bypassed");
        Safety.homeClaimAllows=false;
        require(!canView.getAsBoolean(),"Pending read survived destination claim denial");
        CreativeTabletAccess.tick(tick);
        closed(member,"Destination claim denial left the authorized member's session or ticket active");
        deny(member,legacy,"Member reopened against the destination claim");
        Safety.homeClaimAllows=true;
        shared=open(member,legacy);
        canView=Network.guard(member,hub,List.of(),BasePermissions.VIEW,shared);
        require(canView.getAsBoolean(),"Restored destination claim still denied a granted member");
        hub.members.remove(member.id); hub.profileRevision++;
        require(!canView.getAsBoolean(),"Pending operation survived revocation");
        CreativeTabletAccess.tick(tick);
        closed(member,"Revoking active viewer failed to end session and release ticket");
        deny(member,legacy,"Revoked viewer reopened the linked storage");

        var disconnected=open(owner,legacy);
        owner.connection.getConnection().live=false;
        require(!disconnected.held(),"Disconnected holder retained session");
        CreativeTabletAccess.tick(tick);
        closed(owner,"Disconnected holder leaked ticket");
        System.out.println("PASS UWU_TABLET_ACCESS_OFFLINE: candidate signing, legacy proof, Survival owner/member, mode transitions, revocation, ordinary Cupid range and ticket release.");
        System.out.println("Offline fixture supplied actors, claims, a loaded home chunk and a menu endpoint. No game or modpack was launched.");
    }
}
