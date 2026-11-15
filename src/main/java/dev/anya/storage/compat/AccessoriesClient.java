package dev.anya.storage.compat;
import dev.anya.storage.Content;
import io.wispforest.accessories.api.client.AccessoriesRendererRegistry;
/** Anya's existing synchronized living-entity layer remains the sole backpack renderer. */
public final class AccessoriesClient {
    public static void register(){AccessoriesRendererRegistry.registerNoRenderer(Content.BACKPACK.get());}
    private AccessoriesClient(){}
}
