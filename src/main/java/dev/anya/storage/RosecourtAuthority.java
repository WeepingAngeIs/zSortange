package dev.anya.storage;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;

/** Cross-check the bounded custody scan against the running jail's verified index on its server thread. */
final class RosecourtAuthority {
    static void verify(MinecraftServer server, Map<UUID, RosecourtJournals.Binding> expected, long deadline) throws IOException {
        checkTime(deadline);
        if (!server.isSameThread() || !ModList.get().isLoaded("minecraftjail") || expected.size() > 8192)
            throw unavailable();
        var index = server.getWorldPath(LevelResource.ROOT).resolve("custody-interface-v1").resolve("custody.v1");
        if (Files.isSymbolicLink(index.getParent())) throw unavailable();
        var attrs = Files.readAttributes(index, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.fileKey() == null || attrs.size() < 8 || attrs.size() > (16L << 20))
            throw unavailable();
        try {
            var service = Class.forName("dev.unyielding.jail.JailCustodyService");
            var type = Class.forName("dev.unyielding.jail.JailCustodyLedger");
            var bindingType = Class.forName("dev.unyielding.jail.JailCustodyLedger$Binding");
            Object ledger = service.getMethod("ledger", MinecraftServer.class).invoke(null, server);
            checkTime(deadline);
            type.getMethod("verifyDisk").invoke(ledger);
            checkTime(deadline);
            if (!(type.getMethod("list").invoke(ledger) instanceof List<?> actual) || actual.size() != expected.size())
                throw unavailable();
            Method id = bindingType.getMethod("id"), owner = bindingType.getMethod("owner");
            Method items = bindingType.getMethod("items"), origins = bindingType.getMethod("origins");
            Method digest = bindingType.getMethod("digest"), state = bindingType.getMethod("state");
            for (Object value : actual) {
                checkTime(deadline);
                if (!bindingType.isInstance(value)) throw unavailable();
                var saved = expected.get(id.invoke(value));
                if (saved == null || !saved.owner().equals(owner.invoke(value))
                        || !saved.items().equals(items.invoke(value)) || !saved.origins().equals(origins.invoke(value))
                        || !(state.invoke(value) instanceof Enum<?> current) || !saved.state().equals(current.name())
                        || !(digest.invoke(value) instanceof byte[] hash) || !MessageDigest.isEqual(saved.digest(), hash))
                    throw unavailable();
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            throw new IOException("Rosecourt could not verify its current custody index. Recovery is paused.", failure);
        }
        BagHistoryPresence.checkUnchanged(index, attrs);
        checkTime(deadline);
    }

    private static void checkTime(long deadline) throws IOException {
        if (System.nanoTime() - deadline >= 0 || Thread.currentThread().isInterrupted()) throw unavailable();
    }

    private static IOException unavailable() {
        return new IOException("Rosecourt's active and saved custody records could not be confirmed. Recovery is paused.");
    }

    private RosecourtAuthority() {}
}
