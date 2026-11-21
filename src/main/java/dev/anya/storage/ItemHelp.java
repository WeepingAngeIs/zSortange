package dev.anya.storage;

import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/** Ordinary item hovers, including Creative search where there may be no player. */
@EventBusSubscriber(modid = AnyaStorage.ID, value = Dist.CLIENT)
public final class ItemHelp {
    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        append(BuiltInRegistries.ITEM.getKey(event.getItemStack().getItem()), event.getToolTip());
    }

    static void append(ResourceLocation id, List<Component> lines) {
        if (!AnyaStorage.ID.equals(id.getNamespace())) return;
        String prefix = "tooltip.anyastorage." + id.getPath() + ".";
        Language language = Language.getInstance();
        for (int i = 1; language.has(prefix + i); i++) {
            lines.add(Component.translatable(prefix + i));
        }
    }

    private ItemHelp() {}
}
