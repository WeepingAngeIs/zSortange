package dev.anya.storage.rendercheck;

import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import java.nio.file.*;
import java.util.*;

/** Disposable renderer control only. No portal, lease adapter, storage transaction or player-world access. */
@Mod(RendererCheck.ID)
@EventBusSubscriber(modid=RendererCheck.ID)
public final class RendererCheck {
    public static final String ID="anyarenderprereq";
    static final BlockPos TARGET=new BlockPos(8,67,10);
    private static MinecraftServer server;
    private static boolean changed;
    public RendererCheck() {
        Path p=control();
        // Existing safety observers require this path before server lifecycle events.
        String old=System.getProperty("anya.control");
        if(old!=null&&!Path.of(old).toAbsolutePath().normalize().equals(p))throw new IllegalStateException("Conflicting fixture output path");
        System.setProperty("anya.control",p.toString());
    }
    static Path control() {
        if(!"true".equals(System.getenv("GITHUB_ACTIONS"))) throw new IllegalStateException("CI-only diagnostic");
        Path p=Path.of(System.getProperty("anya.rendererControl")).toAbsolutePath().normalize();
        if(!Files.isRegularFile(p.resolve(".disposable-renderer-prereq")))throw new IllegalStateException("Disposable marker missing");
        return p;
    }
    static void write(String name,Object value) throws Exception {
        Files.writeString(control().resolve(name),new GsonBuilder().setPrettyPrinting().create().toJson(value)+"\n");
    }
    static void fail(Throwable error){try{write("FAILED",Map.of("error",error.toString()));}catch(Exception nested){error.printStackTrace();}}
    static void wall(boolean green){
        var l=server.overworld();
        for(int x=4;x<=12;x++)for(int y=64;y<=72;y++)l.setBlock(new BlockPos(x,y,10),(green?Blocks.LIME_CONCRETE:Blocks.RED_CONCRETE).defaultBlockState(),3);
    }
    @SubscribeEvent public static void start(ServerStartedEvent e){
        try {
            control();server=e.getServer();var l=server.overworld();
            if(!Boolean.getBoolean("anya.repairJournal")||!Boolean.getBoolean("anya.serializationBudget")||!Boolean.getBoolean("anya.backpressure"))throw new IllegalStateException("Current safety stack flags missing");
            server.setDefaultGameType(GameType.SPECTATOR);
            for(int x=0;x<17;x++)for(int z=0;z<17;z++)l.setBlock(new BlockPos(x,64,z),Blocks.STONE.defaultBlockState(),3);
            wall(false);l.setDayTime(6000);
            write("server-ready",Map.of("scene","direct-world red/green control; CE absent","saveSafety","loaded, not fault-tested"));
        }catch(Throwable t){fail(t);e.getServer().halt(false);}
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent e){
        if(e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p){
            p.setGameMode(GameType.SPECTATOR);p.teleportTo(server.overworld(),8.5,65.5,2.5,0,0);
        }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post e){
        if(server==null)return;
        try {
            if(server.getTickCount()>4000)throw new IllegalStateException("Server tick ceiling");
            if(!changed&&Files.exists(control().resolve("red-captured"))){wall(true);changed=true;write("green-sent",Map.of("tick",server.getTickCount()));}
            if(Files.exists(control().resolve("client-finished"))){server.saveEverything(true,true,true);server.halt(false);}
        }catch(Throwable t){fail(t);server.halt(false);}
    }
    @SubscribeEvent public static void stop(ServerStoppedEvent e){try{write("server-stopped",Map.of("clean",true));}catch(Exception x){fail(x);}}
}
