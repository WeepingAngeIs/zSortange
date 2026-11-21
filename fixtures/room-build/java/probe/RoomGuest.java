package probe;

import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import dev.anya.storage.pocket.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid="buildprobe",value=Dist.CLIENT)
public final class RoomGuest {
    static int stage,firstPixels;
    static void mark(String s)throws Exception{Files.writeString(Path.of(s),"ok");stage++;}
    @SubscribeEvent public static void tick(ClientTickEvent.Post e)throws Exception{
        var mc=Minecraft.getInstance();
        if(Files.exists(Path.of("../Server/FAILED"))){mc.stop();return;}
        if(Files.exists(Path.of("../Server/entry.txt"))){
            Files.writeString(Path.of("CHECKS.txt"),"PASS real exterior client received and drew sideways log, different pixels on rotation, location claim and View revocation, dynamic removal and replacement.\n");mc.stop();return;
        }
        if(mc.level==null || !(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit)
                || !(mc.level.getBlockEntity(hit.getBlockPos()) instanceof PocketContent.EntranceEntity door))return;
        int mask=EntranceLayoutClient.visibleMask(door);
        Class<?> cache=Class.forName("dev.anya.storage.pocket.RoomCoverImage");
        var field=cache.getDeclaredField("strips");field.setAccessible(true);
        int[] strip=((int[][])field.get(null))[7];int state=0;
        if(strip!=null)for(int entry:strip)if((entry&255)==15*8)state=entry>>>8;
        int x=Block.getId(Blocks.CHERRY_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS,Direction.Axis.X));
        int z=Block.getId(Blocks.CHERRY_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS,Direction.Axis.Z));
        var textureField=cache.getDeclaredField("texture");textureField.setAccessible(true);
        var texture=(DynamicTexture)textureField.get(null);int hash=1,drawn=0;
        if(texture!=null && texture.getPixels()!=null){
            // Front-left portion of the log, outside the backing stone's stamp.
            for(int yy=150;yy<160;yy++)for(int xx=74;xx<80;xx++){
                int pixel=texture.getPixels().getPixelRGBA(xx,yy);hash=hash*31+pixel;if(pixel!=0)drawn++;
            }
        }
        if(mc.player!=null && mc.player.tickCount%100==0)System.out.println("Room cover probe stage="+stage+" mask="+mask+" state="+state+" pixels="+drawn);
        switch(stage){
            case 0->{if(mask>=0 && state==x && drawn>0){firstPixels=hash;mark("side");}}
            case 1->{if(state==z && drawn>0 && hash!=firstPixels)mark("rotate");}
            case 2->{if(mask>=0 && state==0 && drawn==0)mark("denied");}
            case 3->{if(state==z && drawn>0)mark("restored");}
            case 4->{if(mask<0 && texture==null)mark("revoked");}
            case 5->{if(mask>=0 && state==z && drawn>0)mark("regained");}
            case 6->{if(state==0 && mask>=0 && drawn==0)mark("removed");}
            case 7->{if(state==x && drawn>0)mark("replaced");}
        }
    }
}
