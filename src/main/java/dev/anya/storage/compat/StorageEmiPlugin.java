package dev.anya.storage.compat;

import dev.anya.storage.Client;
import dev.anya.storage.ui.StorageGrid;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.widget.Bounds;

/** EMI discovers this client integration when installed. Coordinates follow the current screen layout. */
@EmiEntrypoint
public final class StorageEmiPlugin implements EmiPlugin {
    @Override
    public void register(EmiRegistry registry) {
        Client.Screen.externalSearchFocus(dev.emi.emi.api.EmiApi::isSearchFocused);
        registry.addScreenBoundsProvider(Client.Screen.class, screen -> bounds(screen.emiMainBounds()));
        registry.addExclusionArea(Client.Screen.class, (screen, exclude) ->
                screen.emiExtraBounds().forEach(box -> exclude.accept(bounds(box))));
    }

    private static Bounds bounds(StorageGrid.Box box) {
        return new Bounds(box.x(), box.y(), box.width(), box.height());
    }
}
