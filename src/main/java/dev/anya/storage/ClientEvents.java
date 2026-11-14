package dev.anya.storage;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid=AnyaStorage.ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
public final class ClientEvents {
    private static KeyMapping quick,dump;
    private ClientEvents(){}
    @SubscribeEvent public static void screens(RegisterMenuScreensEvent event){event.register(AnyaStorage.STORAGE_MENU.get(),StorageScreen::new);}
    @SubscribeEvent public static void keys(RegisterKeyMappingsEvent event){
        quick=new KeyMapping("key.anyastorage.quickstack",InputConstants.Type.KEYSYM,InputConstants.UNKNOWN.getValue(),"key.categories.anyastorage");
        dump=new KeyMapping("key.anyastorage.dump",InputConstants.Type.KEYSYM,InputConstants.UNKNOWN.getValue(),"key.categories.anyastorage");
        event.register(quick);event.register(dump);
    }
    @SubscribeEvent public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event){event.registerLayerDefinition(StorageChestRenderer.LAYER,StorageChestRenderer::layer);}
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event){event.registerBlockEntityRenderer(AnyaStorage.STORAGE_ENTITY.get(),StorageChestRenderer::new);}
    @EventBusSubscriber(modid=AnyaStorage.ID,value=Dist.CLIENT)
    public static final class GameEvents {
        @SubscribeEvent public static void tick(ClientTickEvent.Post event){
            Minecraft mc=Minecraft.getInstance();
            if(quick==null||dump==null)return;
            while(quick.consumeClick())if(mc.player!=null&&mc.screen==null)PacketDistributor.sendToServer(new Packets.Shortcut(0));
            while(dump.consumeClick())if(mc.player!=null&&mc.screen==null)PacketDistributor.sendToServer(new Packets.Shortcut(1));
        }
    }
}
