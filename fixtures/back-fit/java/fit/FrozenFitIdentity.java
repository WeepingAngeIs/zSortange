package fit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Record the binary the real client loaded, independently of the fit and shutdown measurements. */
@EventBusSubscriber(modid="backfitcheck", value=Dist.CLIENT)
public final class FrozenFitIdentity {
    private static boolean checked;

    @SubscribeEvent public static void onTick(ClientTickEvent.Post event) throws Exception {
        if (checked) return;
        checked = true;
        String expected = System.getenv("ANYA_FROZEN_BACKPACK");
        if (expected == null) return;
        Path loaded = ModList.get().getModContainerById("anyastorage").orElseThrow().getModInfo()
                .getOwningFile().getFile().getFilePath();
        if (!Files.isRegularFile(loaded) || !Files.isSameFile(loaded, Path.of(expected)))
            throw new IllegalStateException("Client loaded a different Storage JAR");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(loaded)));
        if (!hash.equals(System.getenv("FROZEN_PLAYABLE_SHA256")))
            throw new IllegalStateException("Loaded Storage JAR changed");
        Path marker = Path.of(System.getProperty("anyastorage.fitRoot")).resolve("loaded-jar.sha256");
        Files.writeString(marker, hash + "\n");
    }
}
