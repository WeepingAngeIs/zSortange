package dev.anya.storage.compat;
import dev.anya.storage.Content;
import io.wispforest.accessories.api.client.AccessoriesRendererRegistry;
/** Anya's existing synchronized living-entity layer remains the sole backpack renderer. */
public final class AccessoriesClient {
    public static void register(){AccessoriesRendererRegistry.registerNoRenderer(Content.BACKPACK.get());}
    public static void verify(){
        if(!AccessoriesRendererRegistry.hasRenderer(Content.BACKPACK.get())||!AccessoriesRendererRegistry.getRenderer(Content.BACKPACK.get()).isEmpty())throw new IllegalStateException("Accessories would render a duplicate generic backpack");
        dev.anya.storage.AnyaStorage.LOG.info("ANYA_ACCESSORIES_CLIENT_OK: native renderer registration loaded; generic duplicate layer suppressed");
    }
    private AccessoriesClient(){}
}
