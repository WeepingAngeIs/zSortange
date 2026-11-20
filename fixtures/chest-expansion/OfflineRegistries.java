package net.neoforged.neoforge.registries;

import dev.anya.storage.Config;
import dev.anya.storage.Content;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;

/** Fires the mod's real registry callbacks in an isolated JVM, without starting a server or level. */
public final class OfflineRegistries {
    public static RegistryAccess setup(Path scratch) {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        FMLPaths.loadAbsolutePaths(scratch);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var bus = BusBuilder.builder().build();
        Content.register(bus);
        register(bus, (MappedRegistry<?>) BuiltInRegistries.BLOCK);
        register(bus, (MappedRegistry<?>) BuiltInRegistries.ITEM);
        register(bus, (MappedRegistry<?>) BuiltInRegistries.BLOCK_ENTITY_TYPE);
        for (var holder : Content.ITEMS.getEntries()) {
            if (holder.get() instanceof BlockItem blockItem) blockItem.registerBlocks(Item.BY_BLOCK, blockItem);
        }
        Config.init();
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    // RegisterEvent's constructor belongs to this package. Only these three registries are needed.
    private static <T> void register(IEventBus bus, MappedRegistry<T> registry) {
        registry.unfreeze();
        bus.post(new RegisterEvent(registry.key(), registry));
        registry.freeze();
    }
}
