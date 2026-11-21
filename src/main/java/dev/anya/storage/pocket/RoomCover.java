package dev.anya.storage.pocket;

import dev.anya.storage.Safety;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** One 28-by-8 strip of the editable volume per focused viewer per tick. State only, no entity data. */
public final class RoomCover {
    static final int WIDTH=28, HEIGHT=8, CELLS=WIDTH*HEIGHT;
    public record Strip(BlockPos entrance,long token,int index,int[] entries,boolean unchanged) implements CustomPacketPayload {
        static final Type<Strip> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("anyastorage","room_cover_strip"));
        public Type<Strip> type(){return TYPE;}
        static final StreamCodec<RegistryFriendlyByteBuf,Strip> CODEC=StreamCodec.ofMember((s,b)->{
            b.writeBlockPos(s.entrance);b.writeLong(s.token);b.writeByte(s.index);b.writeBoolean(s.unchanged);b.writeVarIntArray(s.entries);
        },b->new Strip(b.readBlockPos(),b.readLong(),b.readUnsignedByte(),b.readBoolean(),b));
        private Strip(BlockPos pos,long token,int x,boolean same,RegistryFriendlyByteBuf b){this(pos,token,x,b.readVarIntArray(CELLS),same);}
    }
    static BiConsumer<Connection,Strip> receiver=(c,s)->{};
    static void register(RegisterPayloadHandlersEvent e){
        e.registrar("cover1").optional().playToClient(Strip.TYPE,Strip.CODEC,(s,c)->receiver.accept(c.connection(),s));
    }
    private final int[][] previous=new int[WIDTH][];
    private int cursor;
    void send(ServerPlayer p,BlockPos entrance,long token,PocketLedger.Pocket room,java.util.function.BooleanSupplier authorized){
        if(!p.connection.hasChannel(Strip.TYPE))return;
        var level=PocketService.dimension(p.getServer());
        if(level==null)return;
        int x=cursor;cursor=(cursor+1)%WIDTH;
        int[] entries=sample(p,level,room,x);
        if(!authorized.getAsBoolean())throw new IllegalStateException("Cover permission changed");
        boolean same=Arrays.equals(entries,previous[x]);previous[x]=entries;
        PacketDistributor.sendToPlayer(p,new Strip(entrance,token,x,same?new int[0]:entries,same));
    }
    static int[] sample(ServerPlayer viewer,ServerLevel level,PocketLedger.Pocket room,int x){
        var blocks=new LoadedPocketBlocks(level);
        int[] cells=new int[CELLS];int count=0;
        var left=new BlockPos(room.x()+x+2,65,room.z()+2);
        if(!StillRepairGuard.permits(room.id(),List.of(left,left.offset(0,0,WIDTH-1))))return new int[0];
        for(int z=0;z<WIDTH;z++)for(int y=0;y<HEIGHT;y++){
            if(!RoomBounds.decoration(x+2,y+65,z+2))continue;
            var pos=left.offset(0,y,z);
            var state=blocks.getBlockState(pos);
            if(state.isAir() || state.is(dev.anya.storage.Content.CRATE.get()) || !RoomEditor.supported(state.getBlock()))continue;
            if(!Safety.locationReason(viewer,level,pos).isEmpty() || blocks.getBlockState(pos)!=state)continue;
            // Furniture can carry private data. Draw its public default casing only.
            int id=Block.getId(state.hasBlockEntity()?state.getBlock().defaultBlockState():state);
            cells[count++]=(id<<8)|(z*HEIGHT+y);
        }
        return blocks.complete()?Arrays.copyOf(cells,count):new int[0];
    }
}
