package dev.anya.storage.pocket;

import dev.anya.storage.BasePermissions;
import dev.anya.storage.Safety;
import dev.anya.storage.StorageEntity;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Production PocketView owns all comparisons. Fixture supplies the live inventory, claims and physical registry. */
public final class BindingCheck {
    private static final ServerPlayer player = new ServerPlayer();
    private static PocketLedger.Pocket room;
    private static ItemStack tablet() {
        var tag = new CompoundTag();
        tag.id = room.id(); tag.pos = room.anchor().hub(); tag.dimension = room.anchor().dimension();
        var item = new ItemStack();
        item.tablet = true; item.data = new CustomData(tag);
        return item;
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static void lease(long now, PocketLeases.Kind kind) {
        PocketService.leases.release(player.id);
        check(PocketService.leases.acquire(player.id, room.id(), kind, room.epoch(), now, 1, 2, 20, 100), "Fixture lease");
    }
    private static void refused(ItemStack item, String reason) throws IOException {
        player.main = item;
        try { PocketView.localTablet(player, room, InteractionHand.MAIN_HAND); }
        catch (IOException expected) {return;}
        throw new AssertionError(reason);
    }
    private static void provenance(Class<?> cls, Path candidate) throws Exception {
        var origin = Path.of(cls.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        check(origin.equals(candidate), cls.getName() + " did not come from the candidate JAR");
    }
    public static void main(String[] args) throws Exception {
        Path candidate = Path.of(args[0]).toRealPath();
        for (Class<?> cls : List.of(PocketView.class, PocketLedger.class, PocketLeases.class, BasePermissions.class))
            provenance(cls,candidate);
        System.out.println("STRUCTURE: PocketView, leases, ledger and permission masks loaded from the supplied playable JAR.");
        var data = new PocketLedger(Path.of("scratch-ledger"));
        PocketService.data = data;
        var anchor = new PocketLedger.Anchor("minecraft:overworld", 100L, UUID.randomUUID(), 101L);
        room = data.reserve(player.id, anchor, "galaxy", 1);
        room = data.stage(room.id(), PocketLedger.Stage.RESERVED, PocketLedger.Stage.BUILDING);
        room = data.stage(room.id(), PocketLedger.Stage.BUILDING, PocketLedger.Stage.READY);
        var chest = new StorageEntity(200L); chest.pocketId = room.id();
        PocketService.level.cells.put(chest.pos, chest);
        PocketRoomLayout.registry = List.of(chest);
        PocketService.rights = BasePermissions.VISIT | BasePermissions.BUILD;
        lease(1, PocketLeases.Kind.VISIT);
        var original = tablet(); player.main = original; player.inv.selected = 3;
        var view = PocketView.localTablet(player, room, InteractionHand.MAIN_HAND);
        var guarded = view.guard(BasePermissions.BUILD);
        check(view.localTablet() && view.tabletSlot()==3 && guarded.getAsBoolean() && !view.permits(BasePermissions.VIEW),
                "Visit+Build grant independently of View");
        PocketService.rights = BasePermissions.VISIT | BasePermissions.VIEW;
        check(!guarded.getAsBoolean() && view.permits(BasePermissions.VIEW), "Queued guard sees revocation");
        PocketService.rights = BasePermissions.ALL;
        Safety.onClaim=()->PocketService.rights=BasePermissions.VISIT;
        check(!guarded.getAsBoolean(),"A claim callback revoked Build during this same check");
        PocketService.rights=BasePermissions.ALL;
        Safety.onClaim=()->player.main=tablet();
        check(!view.valid(),"A claim callback swapped the held tablet during this same check");
        player.main=original;
        Safety.onClaim=()->PocketRoomLayout.registry=List.of();
        check(!view.valid(),"A claim callback changed the registered chests during this same check");
        PocketRoomLayout.registry=List.of(chest);
        view.close();
        check(PocketService.remoteCloses==0 && view.valid(), "Closing a local tablet must preserve the physical Visit");
        player.main = tablet();
        check(!view.valid(), "A second, identically bound item cannot inherit an open panel");
        player.main = original;
        var binding = original.data;
        original.data = new CustomData(binding.tag());
        check(!view.valid(), "Same stack with replaced binding data must invalidate the panel");
        original.data = binding;
        original.count = 2;
        check(!view.valid(), "Stacked tablet cannot drive controls");
        original.count = 1;
        player.off = original; player.main = ItemStack.EMPTY;
        check(!view.valid(), "Moving the open tablet to the other hand invalidates its old panel");
        var off = PocketView.localTablet(player,room,InteractionHand.OFF_HAND);
        check(off.valid() && off.tabletSlot()==-1, "Offhand opens independently and excludes no main-inventory slot");
        PocketService.hub.profileRevision++;
        check(!off.valid(),"Policy revision invalidated an existing view");
        System.out.println("BEHAVIOR: local close, per-hand ownership, binding identity, stack size and queued permission revocation.");

        var wrong = tablet(); wrong.data.tag().id = UUID.randomUUID(); refused(wrong,"Foreign room UUID");
        wrong = tablet(); wrong.data.tag().pos = 302L; refused(wrong,"Foreign home hub");
        wrong = tablet(); wrong.data.tag().pos = null; refused(wrong,"Missing long position");
        wrong = tablet(); wrong.data.tag().dimension="minecraft:the_nether"; refused(wrong,"Foreign hub dimension");
        wrong = tablet(); wrong.tablet=false; refused(wrong,"Other item copied the tablet data");
        var uwu = tablet(); uwu.creative=true; refused(uwu,"Unsigned UwU Tablet in Survival");
        uwu.sealed=true; player.main=uwu;
        var uwuView=PocketView.localTablet(player,room,InteractionHand.MAIN_HAND);
        check(uwuView.valid(),"Locally signed UwU Tablet in Survival");
        player.spectator=true;
        check(!uwuView.valid(),"Spectating revoked the live panel");
        player.spectator=false;
        System.out.println("BEHAVIOR: pocket, home position, dimension, tablet kind, Survival and spectator gating.");

        player.main=original;
        view=PocketView.localTablet(player,room,InteractionHand.MAIN_HAND);
        check(PocketService.leases.renew(player.id,room.epoch(),8,20) && view.valid(),"Same visit renewal");
        lease(12,PocketLeases.Kind.VISIT);
        check(!view.valid(),"New visit to same room cannot inherit old panel");
        view=PocketView.localTablet(player,room,InteractionHand.MAIN_HAND);
        PocketRoomLayout.registry=List.of();
        check(!view.valid(),"Registry changed while panel was open");
        PocketRoomLayout.registry=List.of(chest);
        PocketService.level.loaded=false;
        check(!view.valid(),"Unloaded chest fails closed");
        PocketService.level.loaded=true;
        PocketService.leases.expire(33);
        check(!view.valid(),"Expired visit");
        lease(40,PocketLeases.Kind.REMOTE);
        refused(original,"Remote lease is not physical room editing");
        var remote=new PocketView(player,room,PocketLeases.Kind.REMOTE);
        check(!remote.localTablet() && remote.tabletSlot()==-1 && remote.valid(),"Ordinary remote view");
        remote.close();
        check(PocketService.remoteCloses==1 && PocketService.lease(player.id)==null,"Remote closure releases only its remote lease");
        System.out.println("BEHAVIOR: renewal, new visit, registry change, unloading, lease expiry and remote closure.");
        System.out.println("PASS ROOM_EDITOR_BINDING_OFFLINE. No game, packet delivery, rendered UI or block interaction exercised.");
    }
}
