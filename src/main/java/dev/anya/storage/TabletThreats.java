package dev.anya.storage;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Samples already-ticking hostiles' actual attack targets. No level searches or chunk requests. */
@EventBusSubscriber(modid = AnyaStorage.ID)
public final class TabletThreats {
    private static final int INTERVAL = 5;
    private static final int WARN_DISTANCE_SQUARED = 12 * 12;
    private static final int LEAVE_DISTANCE_SQUARED = 16 * 16;
    private static final Map<ServerPlayer, ThreatEncounter<Mob>> encounters = new HashMap<>();

    @SubscribeEvent public static void mobTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)) return;
        if (Math.floorMod((long)mob.tickCount + mob.getId(), INTERVAL) != 0 || !mob.isAlive()) return;
        if (!(mob instanceof Enemy) && mob.getType().getCategory() != MobCategory.MONSTER) return;
        if (!(attackTarget(mob) instanceof ServerPlayer player) || player.level() != level ||
                !player.isAlive() || player.isSpectator() || level.getServer().getPlayerList().getPlayer(player.getUUID())!=player) return;
        double distance = mob.distanceToSqr(player);
        if (distance > LEAVE_DISTANCE_SQUARED) return;

        var encounter = encounters.computeIfAbsent(player, ignored -> new ThreatEncounter<>(Mob::getId));
        encounter.targeted(level.getServer().overworld().getGameTime());
        if (distance <= WARN_DISTANCE_SQUARED) encounter.offer(mob);
    }

    private static LivingEntity attackTarget(Mob mob) {
        var target = mob.getTarget();
        if (target != null) return target;
        var brain = mob.getBrain();
        return brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                ? brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) : null;
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var server = event.getServer();
        long now = server.overworld().getGameTime();
        if (now % INTERVAL != 0) return;
        var entries = encounters.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            var player = entry.getKey();
            var encounter = entry.getValue();
            if (player.getServer() != server) continue;
            if (encounter.expired(now) || player.isRemoved() || !player.isAlive() || player.isSpectator()) {
                entries.remove();
                continue;
            }
            boolean carrying = player.connection != null && player.connection.getConnection().isConnected()
                    && player.connection.hasChannel(TabletThreatCue.TYPE) && carriesTablet(player);
            Mob visible = encounter.check(carrying, mob -> visibleThreat(mob, player));
            if (visible != null) {
                int direction = ThreatEncounter.direction(visible.getX() - player.getX(),
                        visible.getZ() - player.getZ(), player.getYRot());
                PacketDistributor.sendToPlayer(player, new TabletThreatCue(direction));
            }
        }
    }

    private static boolean visibleThreat(Mob mob, ServerPlayer player) {
        return mob.isAlive() && !mob.isRemoved() && mob.level() == player.level() && attackTarget(mob) == player
                && mob.distanceToSqr(player) <= WARN_DISTANCE_SQUARED && !mob.isInvisibleTo(player)
                && player.hasLineOfSight(mob) && mob.hasLineOfSight(player);
    }

    private static boolean carriesTablet(ServerPlayer player) {
        if (StorageItem.isTablet(player.getOffhandItem())) return true;
        var inventory = player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (StorageItem.isTablet(inventory.getItem(slot))) return true;
        }
        return false;
    }

    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) encounters.remove(player);
    }

    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) encounters.remove(player);
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        encounters.clear();
    }
}
