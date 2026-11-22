package dev.anya.storage;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class OperatorPage {
    public record Request(int ticket,long revision,String search,int offset,int size,String key,String value,boolean confirm) implements CustomPacketPayload {
        static final Type<Request> TYPE=new Type<>(ExpansionPackets.id("operator_edit"));
        public Type<Request> type(){return TYPE;}
    }
    public record Cell(String key,String label,String help,String value,boolean toggle){}
    public record View(int ticket,long revision,int offset,int total,List<Cell> cells,String status,String confirmation) implements CustomPacketPayload {
        static final Type<View> TYPE=new Type<>(ExpansionPackets.id("operator_rows"));
        public Type<View> type(){return TYPE;}
    }
    private static final SettingsConfirmation confirmations=new SettingsConfirmation();
    public static Consumer<View> receiver=view->{};
    private static final StreamCodec<RegistryFriendlyByteBuf,Request> REQUEST=new StreamCodec<>(){
        public Request decode(RegistryFriendlyByteBuf b){return new Request(b.readInt(),b.readLong(),b.readUtf(80),b.readVarInt(),b.readVarInt(),b.readUtf(512),b.readUtf(256),b.readBoolean());}
        public void encode(RegistryFriendlyByteBuf b,Request r){b.writeInt(r.ticket);b.writeLong(r.revision);b.writeUtf(r.search,80);b.writeVarInt(r.offset);b.writeVarInt(r.size);b.writeUtf(r.key,512);b.writeUtf(r.value,256);b.writeBoolean(r.confirm);}
    };
    private static final StreamCodec<RegistryFriendlyByteBuf,View> VIEW=new StreamCodec<>(){
        public View decode(RegistryFriendlyByteBuf b){
            int ticket=b.readInt();long revision=b.readLong();int offset=b.readVarInt(),total=b.readVarInt();
            var cells=b.readList(buf->new Cell(buf.readUtf(),buf.readUtf(),buf.readUtf(),buf.readUtf(),buf.readBoolean()));
            return new View(ticket,revision,offset,total,cells,b.readUtf(),b.readUtf());
        }
        public void encode(RegistryFriendlyByteBuf b,View v){
            b.writeInt(v.ticket);b.writeLong(v.revision);b.writeVarInt(v.offset);b.writeVarInt(v.total);
            b.writeCollection(v.cells,(buf,c)->{buf.writeUtf(c.key);buf.writeUtf(c.label);buf.writeUtf(c.help);buf.writeUtf(c.value);buf.writeBoolean(c.toggle);});
            b.writeUtf(v.status);b.writeUtf(v.confirmation);
        }
    };
    static void register(RegisterPayloadHandlersEvent e){
        var channel=e.registrar("4");
        channel.playToServer(Request.TYPE,REQUEST,(r,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer p)handle(p,r,v->PacketDistributor.sendToPlayer(p,v));}));
        channel.playToClient(View.TYPE,VIEW,(v,c)->c.enqueueWork(()->receiver.accept(v)));
    }
    public static void handle(ServerPlayer p,Request r,Consumer<View> send){
        if(!p.getServer().isSameThread())return;
        if(!p.hasPermissions(2)){confirmations.clear(p);send.accept(new View(r.ticket,-1,0,0,List.of(),"Operator permission required.",""));return;}
        String note="",confirm="";
        if(r.key.isEmpty()) confirmations.clear(p);
        if(!r.key.isEmpty()){
            note=Config.saveProblem(p,r.revision);
            if(!note.isEmpty()) confirmations.clear(p);
            else{
                var draft=Config.copy(Config.get());
                try{
                    var row=OperatorOptions.rows(draft).stream().filter(x->x.key().equals(r.key)).findFirst().orElseThrow(()->new IllegalArgumentException("This setting is no longer available."));
                    if(!row.confirm()) confirmations.clear(p);
                    if(row.confirm() && !confirmations.accept(p,r.revision,r.key,r.value,r.confirm)){
                        note="Confirm "+row.label()+". "+row.help();confirm=r.key;
                    }else{
                        row.edit().accept(r.value.strip());
                        String[] result={""};
                        boolean[] returned={false};
                        boolean accepted=Config.saveFromScreen(p,draft,message->{
                            if(!returned[0])result[0]=message;
                            else if(p.getServer().getPlayerList().getPlayer(p.getUUID())==p)reply(p,r,message,"",send);
                        });
                        returned[0]=true;
                        note=accepted?(Config.reloadPending()?"Saved "+row.label()+". Reloading settings…":"Saved "+row.label()+"."):result[0];
                        if(accepted && result[0].contains("restart"))note=result[0];
                    }
                }catch(IllegalArgumentException ex){confirmations.clear(p);note=ex instanceof NumberFormatException?"Enter a valid whole number.":ex.getMessage();}
            }
        }
        reply(p,r,note,confirm,send);
    }
    private static void reply(ServerPlayer p,Request r,String note,String confirm,Consumer<View> send){
        if(!p.hasPermissions(2)){send.accept(new View(r.ticket,-1,0,0,List.of(),"Operator permission required.",""));return;}
        String query=r.search.strip().toLowerCase(Locale.ROOT);
        var rows=OperatorOptions.rows(Config.get()).stream().filter(x->(x.label()+" "+x.key()+" "+x.help()).toLowerCase(Locale.ROOT).contains(query)).toList();
        int count=Math.clamp(r.size,1,16),offset=Math.clamp(r.offset,0,Math.max(0,rows.size()-count));
        var cells=rows.stream().skip(offset).limit(count).map(x->new Cell(x.key(),x.label(),x.help(),x.value(),x.toggle())).toList();
        send.accept(new View(r.ticket,Config.get().revision,offset,rows.size(),cells,note,confirm));
    }
}
