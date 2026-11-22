package probe;

import dev.anya.storage.Packets;
import dev.anya.storage.StoreMenu;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid="chestsyncprobe", value=Dist.CLIENT)
public final class ChestClient {
    private static String sent="";
    private static int ticks;
    private static final Path SERVER=Path.of("../Server");
    @SubscribeEvent public static void chat(net.neoforged.neoforge.client.event.ClientChatReceivedEvent.System event) throws Exception {
        if(!"Client".equals(System.getProperty("chest.sync.role")))return;
        String message=event.getMessage().getString();
        if(message.contains("No capacity or the destination filter rejects this item"))
            Files.writeString(SERVER.resolve("rejected-offerFull"),"Real server rejected full destination");
        if(message.contains("Your main inventory is full"))
            Files.writeString(SERVER.resolve("rejected-takeFull"),"Real server rejected full player inventory");
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        var game=Minecraft.getInstance();
        if(Files.exists(SERVER.resolve("FAILED")) || Files.exists(SERVER.resolve("upgrade.pass"))){game.stop();return;}
        if(game.player==null || game.gameMode==null)return;
        if(Files.exists(SERVER.resolve("layout-play.pass"))){game.stop();return;}
        if("Builder".equals(System.getProperty("chest.sync.role")) && Files.isRegularFile(SERVER.resolve("layout-shift"))){
            boolean active=!Files.exists(SERVER.resolve("layout-unshift"));
            game.options.keyShift.setDown(active);
            Path aim=SERVER.resolve("layout-attach");
            if(active && Files.isRegularFile(aim) && !Files.exists(SERVER.resolve("layout-sent")) &&
                    game.player.getMainHandItem().is(dev.anya.storage.Content.CRATE.get().asItem())){
                String location=Files.readString(aim).strip();if(location.isEmpty())return;
                var target=net.minecraft.core.BlockPos.of(Long.parseLong(location));
                if(game.player.pick(game.player.blockInteractionRange(),1,false) instanceof net.minecraft.world.phys.BlockHitResult hit &&
                        hit.getType()==net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(target) &&
                        hit.getDirection()==net.minecraft.core.Direction.SOUTH && game.player.isSecondaryUseActive()){
                    game.gameMode.useItemOn(game.player,InteractionHand.MAIN_HAND,hit);
                    game.player.swing(InteractionHand.MAIN_HAND);
                    Files.writeString(SERVER.resolve("layout-sent"),"Client sent crouched use on labelled south face");
                }
            }
            return;
        }
        if("Client".equals(System.getProperty("chest.sync.role")) && Files.isRegularFile(SERVER.resolve("layout-expect")) &&
                game.player.containerMenu instanceof StoreMenu layout && layout.filteredView() && !Files.exists(SERVER.resolve("layout-seen"))){
            var expected=Files.readAllLines(SERVER.resolve("layout-expect"));
            if(!expected.isEmpty() && ChestLayout.shown(layout).equals(expected))
                Files.writeString(SERVER.resolve("layout-seen"),"All named stacks and exact counts visible on client");
        }
        if("Builder".equals(System.getProperty("chest.sync.role"))){
            Path job=SERVER.resolve("guest-place"),ack=SERVER.resolve("guest-placed");
            if(Files.exists(ack) || !Files.isRegularFile(job) || !game.player.getMainHandItem().is(Items.CHEST))return;
            String jobText=Files.readString(job).trim();if(jobText.isEmpty())return;
            var target=net.minecraft.core.BlockPos.of(Long.parseLong(jobText));
            var hit=game.player.pick(game.player.blockInteractionRange(),1,false);
            if(hit instanceof net.minecraft.world.phys.BlockHitResult block && block.getType()==net.minecraft.world.phys.HitResult.Type.BLOCK && block.getBlockPos().equals(target) && block.getDirection()==net.minecraft.core.Direction.UP){
                game.gameMode.useItemOn(game.player,InteractionHand.MAIN_HAND,block);
                game.player.swing(InteractionHand.MAIN_HAND);
                Files.writeString(ack,"Sent normal chest placement from second connected Minecraft client");
            }
            return;
        }
        if(!Files.isRegularFile(SERVER.resolve("request")))return;
        String request=Files.readString(SERVER.resolve("request")).trim();
        if(request.isEmpty() || request.equals("done") || Files.exists(SERVER.resolve("rejected-"+request)) || request.equals(sent) && ++ticks<15)return;
        if(request.equals("open")){
            if(game.player.containerMenu!=game.player.inventoryMenu || !game.player.getMainHandItem().is(dev.anya.storage.Content.CUPID_TABLET.get()))return;
            game.gameMode.useItem(game.player,InteractionHand.MAIN_HAND);
        }else{
            if(!(game.player.containerMenu instanceof StoreMenu menu) || !menu.storageView())return;
            int slot=-1;
            if(request.startsWith("offer")){
                for(int i=54;i<90;i++)if(menu.getSlot(i).getContainerSlot()==9&&menu.getSlot(i).getItem().is(Items.AMETHYST_SHARD))slot=i;
            }else if(request.equals("find")){
                PacketDistributor.sendToServer(new Packets.Action(menu.containerId,menu.session(),menu.revision(),"text","amethyst",0));
            }else{
                for(int i=0;i<45;i++)if(menu.getSlot(i).getItem().is(Items.AMETHYST_SHARD)){slot=i;break;}
            }
            if(!request.equals("find")){
                if(slot<0)return;
                PacketDistributor.sendToServer(new Packets.Action(menu.containerId,menu.session(),menu.revision(),"slot","",slot));
            }
        }
        sent=request;ticks=0;
        Files.writeString(SERVER.resolve("sent-"+request),"Sent by connected Minecraft client\n");
    }
}
