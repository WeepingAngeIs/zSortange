package dev.anya.storage.guide;

import dev.anya.storage.ExpansionPackets;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.*;
import java.util.function.Consumer;

public record GuidePacket(long revision,List<GuidePage> pages) implements CustomPacketPayload {
    public static final Type<GuidePacket> TYPE=new Type<>(ExpansionPackets.id("guide"));
    public static Consumer<GuidePacket> receiver=p->{};
    public GuidePacket {if(revision<0||pages.isEmpty()||pages.size()>128)throw new IllegalArgumentException("Invalid guide snapshot");pages=List.copyOf(pages);}
    public static final StreamCodec<RegistryFriendlyByteBuf,GuidePacket> CODEC=new StreamCodec<>(){
        public GuidePacket decode(RegistryFriendlyByteBuf b){
            long revision=b.readVarLong();int count=b.readVarInt();if(count<1||count>128)throw new IllegalArgumentException("Guide page limit");List<GuidePage> pages=new ArrayList<>();
            for(int i=0;i<count;i++){
                String chapter=b.readUtf(32),title=b.readUtf(64),art=b.readUtf(128),body=b.readUtf(2048),note=b.readUtf(256),recipe=b.readUtf(160);
                int n=b.readVarInt();if(n<0||n>9)throw new IllegalArgumentException("Guide recipe limit");List<GuidePage.Cell> cells=new ArrayList<>();
                for(int j=0;j<n;j++)cells.add(new GuidePage.Cell(b.readUtf(128),b.readUtf(128)));
                pages.add(new GuidePage(chapter,title,art,body,note,recipe,cells));
            }
            return new GuidePacket(revision,pages);
        }
        public void encode(RegistryFriendlyByteBuf b,GuidePacket p){
            b.writeVarLong(p.revision);b.writeVarInt(p.pages.size());
            for(GuidePage page:p.pages){b.writeUtf(page.chapter(),32);b.writeUtf(page.title(),64);b.writeUtf(page.art(),128);b.writeUtf(page.body(),2048);b.writeUtf(page.note(),256);b.writeUtf(page.recipe(),160);b.writeVarInt(page.cells().size());for(var c:page.cells()){b.writeUtf(c.id(),128);b.writeUtf(c.label(),128);}}
        }
    };
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
    public static void register(RegisterPayloadHandlersEvent event){event.registrar("3").playToClient(TYPE,CODEC,(p,c)->c.enqueueWork(()->receiver.accept(p)));}
}
