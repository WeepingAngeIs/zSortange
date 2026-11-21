package probe;

import java.nio.file.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

final class PokemonProbe {
    private static Mob create(ServerLevel level,String species,Vec3 pos)throws Exception{
        var props=Class.forName("com.cobblemon.mod.common.api.pokemon.PokemonProperties");
        var companion=props.getField("Companion").get(null);
        var parsed=companion.getClass().getMethod("parse",String.class).invoke(companion,species);
        var pokemon=(Mob)props.getMethod("createEntity",Level.class).invoke(parsed,level);
        pokemon.setPos(pos);pokemon.setNoAi(true);pokemon.setNoGravity(true);
        CameraProbe.check(level.addFreshEntity(pokemon),"Spawn "+species);return pokemon;
    }
    static void step(MinecraftServer server)throws Exception{
        if(server.getTickCount()<20 || Files.exists(Path.of("POKEMON-PASS")))return;
        var l=server.overworld();var eye=Vec3.atCenterOf(l.getSharedSpawnPos()).add(0,45,0);
        l.getChunk(BlockPos.containing(eye));var actor=FakePlayerFactory.getMinecraft(l);actor.setPos(eye);
        var cow=EntityType.COW.create(l);cow.setPos(eye.add(0,0,2));l.addFreshEntity(cow);
        CameraProbe.check(WallProbe.glance(actor,l,eye)==cow,"Control cow");
        for(String species:new String[]{"bulbasaur","mewtwo"}){
            var mon=create(l,species,eye.add(5,0,0));
            CameraProbe.check(WallProbe.glance(actor,l,eye)==mon,"Real wild "+species+" lost priority to nearer cow");
            mon.setInvisible(true);CameraProbe.check(WallProbe.glance(actor,l,eye)==cow,"Invisible "+species+" disclosed");mon.discard();
        }
        var common=create(l,"rattata",eye.add(5,0,0));
        CameraProbe.check(WallProbe.glance(actor,l,eye)==cow,"Common species incorrectly preferred");common.discard();cow.discard();
        Files.writeString(Path.of("POKEMON-PASS"),"PASS pinned Cobblemon 1.8.4 rare-only Bulbasaur and legendary Mewtwo selected over nearer cow, common Rattata and invisible Pokemon excluded from priority.\n");
        server.halt(false);
    }
}
