package dev.anya.storage.furniture;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import java.util.*;

/** Runs without a world, registry bootstrap, game window or server. */
public final class FurnitureWireCheck {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),null);
        var pos=new BlockPos(30,67,-119);
        var type=ResourceLocation.parse("minecraft:written_book");
        var display=new ShelfPackets.Display(pos,68874L,type,"A page from home");
        ShelfPackets.Display.CODEC.encode(buf,display);
        check(buf.readBlockPos().equals(pos) && buf.readLong()==68874L && buf.readBoolean() && buf.readResourceLocation().equals(type)
                && buf.readUtf(80).equals("A page from home") && !buf.isReadable());
        buf.clear();
        ShelfPackets.Display.CODEC.encode(buf,display);
        check(ShelfPackets.Display.CODEC.decode(buf).equals(display) && !buf.isReadable());
        buf.clear();
        var cleared=new ShelfPackets.Display(pos,68875L,null,"");
        ShelfPackets.Display.CODEC.encode(buf,cleared);
        check(buf.readableBytes()==17 && ShelfPackets.Display.CODEC.decode(buf).equals(cleared));
        buf.clear();
        var focus=new ShelfPackets.Focus(pos,71);
        ShelfPackets.Focus.CODEC.encode(buf,focus);
        check(buf.readableBytes()==16 && ShelfPackets.Focus.CODEC.decode(buf).equals(focus));
        buf.release();
        check(!Container.class.isAssignableFrom(FurnitureEntity.class));
        ShelfGrant grant=new ShelfGrant();
        long first=grant.request(100,0);
        long newer=grant.request(106,120_000_000L);
        check(!grant.consume(first,true,107,150_000_000L) && !grant.visible(107,150_000_000L));
        check(grant.consume(newer,true,108,180_000_000L) && grant.visible(110,400_000_000L));
        check(!grant.visible(110,620_000_000L) && !grant.visible(116,400_000_000L));
        first=grant.request(120,700_000_000L);
        check(!grant.consume(first,true,123,1_200_000_000L));
        newer=grant.request(125,1_300_000_000L);
        check(grant.consume(newer,true,126,1_350_000_000L));
        first=grant.request(131,1_450_000_000L);
        check(!grant.consume(first,false,132,1_450_000_001L) && !grant.visible(132,1_450_000_002L));
        check(!grant.consume(first,true,132,1_450_000_003L));
        first=grant.request(140,2_000_000_000L);
        grant.clear();
        check(!grant.consume(first,true,141,2_100_000_000L));
        check(Furniture.text("  first catch  ").equals("first catch"));
        for (String bad:List.of("x".repeat(81),"one\ntwo","a\u202eb","§k")) {
            boolean denied=false;
            try { Furniture.text(bad); } catch (IllegalArgumentException expected) { denied=true; }
            check(denied);
        }
        System.out.println("PASS shelf wire fields, empty denial, send-time expiry, old/duplicate/cleared grant rejection, no automation, caption limits");
    }
}
