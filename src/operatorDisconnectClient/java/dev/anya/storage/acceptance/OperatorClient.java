package dev.anya.storage.acceptance;
import dev.anya.storage.AnyaStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
/** Sends each requested command once and reports the actual system-chat result, including issued receipts. */
@EventBusSubscriber(modid=Acceptance.ID,value=Dist.CLIENT)
public final class OperatorClient {
 private static OperatorNet.Message pending;private static String wanted,command;private static boolean sent,ready;private static int ticks,readyTicks;private static String seen="";
 public static void receive(OperatorNet.Message m){
  Acceptance.require(pending==null&&m.verb().equals("command"),"Overlapping/unknown operator directive");int split=m.text().indexOf('\n');Acceptance.require(split>0,"Missing expected response");
  pending=m;wanted=m.text().substring(0,split);command=m.text().substring(split+1);sent=false;ticks=0;seen="";
 }
 @SubscribeEvent public static void chat(ClientChatReceivedEvent e){
  if(!Boolean.getBoolean("anya.operatorNetwork")||pending==null||!sent||!e.isSystem())return;
  String text=e.getMessage().getString();seen+=(seen.length()<4096?" | "+text:"");AnyaStorage.LOG.info("OPERATOR_NETWORK_CHAT sequence={} text={}",pending.sequence(),text);
  if(!text.contains(wanted))return;
  var mc=Minecraft.getInstance();Screenshot.grab(mc.gameDirectory,"operator-"+System.getProperty("anya.role")+"-"+pending.sequence()+".png",mc.getMainRenderTarget(),c->{});
  PacketDistributor.sendToServer(new OperatorNet.Message(pending.sequence(),"result",text));pending=null;
 }
 @SubscribeEvent public static void tick(ClientTickEvent.Post e){
  if(!Boolean.getBoolean("anya.operatorNetwork"))return;var mc=Minecraft.getInstance();
  try{
   if(mc.player==null||mc.level==null||mc.getOverlay()!=null||mc.screen instanceof ReceivingLevelScreen){readyTicks=0;return;}
   if(!ready&&++readyTicks>=15){PacketDistributor.sendToServer(new OperatorNet.Message(0,"ready",System.getProperty("anya.role")));ready=true;}
   if(pending==null)return;
   if(++ticks==10){Acceptance.require(mc.player.connection!=null,"No live connection");sent=true;AnyaStorage.LOG.info("OPERATOR_NETWORK_SEND_ONCE sequence={} command={}",pending.sequence(),command);mc.player.connection.sendCommand(command);}
   Acceptance.require(ticks<=240,"Operator response timeout: wanted="+wanted+" command="+command+" received="+seen);
  }catch(Throwable x){Acceptance.marker("FAILED-operator-client-"+System.getProperty("anya.role"),x.toString());AnyaStorage.LOG.error("OPERATOR_CLIENT_FAILED",x);mc.stop();}
 }
 private OperatorClient(){}
}
