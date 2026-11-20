package dev.anya.storage.pocket;

import java.lang.reflect.Method;
import java.util.UUID;
import net.neoforged.fml.ModList;

/** The optional hosted repair module owns its quarantine decisions. A missing active hook closes the peek. */
final class StillRepairGuard {
    private static final boolean ENABLED = Boolean.getBoolean("anya.repairJournal");
    private record Checks(Method room, Method column) {}
    private static final Checks CHECKS = resolve();

    private static Checks resolve() {
        if (!ENABLED || !ModList.get().isLoaded("anyadurableprobe")) return null;
        try {
            Class<?> api = Class.forName("dev.anya.storage.repair.RepairService", false, StillRepairGuard.class.getClassLoader());
            return new Checks(api.getMethod("roomBlocked", UUID.class), api.getMethod("blocked", long.class));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    static boolean permits(UUID room, long column) {
        if (!ENABLED) return true;
        if (CHECKS == null) return false;
        try {
            return Boolean.FALSE.equals(CHECKS.room.invoke(null, room)) && Boolean.FALSE.equals(CHECKS.column.invoke(null, column));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private StillRepairGuard() {}
}
