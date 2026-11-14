package dev.anya.storage;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Dependency-light tests; the Minecraft registry is exercised separately by GameTests. */
public final class CoreTests {
    private static int assertions;
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static void rejected(Consumer<Rules.Config> edit,String reason){
        Rules.Config config=new Rules.Config();edit.accept(config);
        try{Rules.validate(config);throw new AssertionError("Accepted invalid rules: "+reason);}catch(IllegalArgumentException|NullPointerException expected){assertions++;}
    }
    public static void main(String[] args)throws Exception{
        Rules.Config defaults=new Rules.Config();Rules.validate(defaults);check(defaults.tiers.size()==5,"five base tiers");
        check(!defaults.compatibility,"compatibility opt-in");check(!defaults.recipes.get("endgame"),"endgame disabled by default");check(defaults.endgameMode==0,"endgame remains undecided");
        check(defaults.allowChests&&defaults.allowBarrels&&!defaults.allowShulkers,"safe container defaults");
        check(defaults.maxRadius>=defaults.baseRadius,"radius monotonic");
        Rules.Config copy=defaults.copy();copy.tiers.getFirst().slots=36;copy.materials.put("test:ingot",true);
        check(defaults.tiers.getFirst().slots==27,"deep tier draft copy");check(defaults.materials.isEmpty(),"deep compatibility copy");
        check(Rules.JSON.toJson(defaults).equals(Rules.JSON.toJson(defaults.copy())),"JSON round trip");
        for(String id:List.of("minecraft:iron_ingot","iceandfire:dragonsteel_fire_ingot","cataclysm:witherite_ingot","custom_mod:ore/high_tier"))check(Rules.validId(id),"valid syntax: "+id);
        for(String id:List.of("IRON","minecraft:"," :x","x:y z","x:../UPPER","http://foo","x:y\n"))check(!Rules.validId(id),"invalid syntax: "+id);
        rejected(c->c.schema=99,"schema");rejected(c->c.revision=-1,"revision");
        rejected(c->c.baseRadius=3,"base radius minimum");rejected(c->c.baseRadius=129,"base radius maximum");
        rejected(c->c.maxRadius=15,"maximum below base");rejected(c->c.maxRadius=129,"maximum above cap");
        rejected(c->c.radiusStep=0,"step minimum");rejected(c->c.radiusStep=33,"step maximum");
        rejected(c->c.radiusCost=0,"cost minimum");rejected(c->c.radiusCost=100001,"cost maximum");
        rejected(c->c.outsideRadius=0,"outside minimum");rejected(c->c.outsideRadius=17,"outside maximum");
        rejected(c->c.scanBudget=0,"scan minimum");rejected(c->c.scanBudget=17,"scan maximum");
        rejected(c->c.scanInterval=19,"interval minimum");rejected(c->c.scanInterval=1201,"interval maximum");
        rejected(c->c.maxContainers=0,"container minimum");rejected(c->c.maxContainers=257,"container maximum");
        rejected(c->c.cooldown=1,"cooldown minimum");rejected(c->c.cooldown=101,"cooldown maximum");
        rejected(c->c.crateSlots=10,"non-row crate size");rejected(c->c.crateSlots=225,"crate cap");
        rejected(c->c.cubeSlots=53,"cube minimum");rejected(c->c.cubeSlots=873,"cube maximum");
        rejected(c->c.endgameMode=3,"unknown endgame route");
        rejected(c->c.recipes.remove("hubs"),"missing recipe group");rejected(c->c.recipes.put("surprise",true),"unknown recipe group");rejected(c->c.recipes.put("hubs",null),"null recipe toggle");
        rejected(c->c.upgradeTypes.clear(),"missing upgrades");rejected(c->c.materials.put("INVALID",true),"invalid material ID");
        rejected(c->{for(int i=0;i<129;i++)c.materials.put("test:i"+i,true);},"material limit");
        rejected(c->c.ores.put("test:ore",0),"zero ore value");rejected(c->c.ores.put("test:ore",10001),"excessive ore value");
        rejected(c->c.categories.put("minecraft:stone","unknown"),"invalid category");
        rejected(c->c.tiers.clear(),"empty progression");rejected(c->c.tiers.add(c.tiers.getFirst()),"duplicate tier ID");
        rejected(c->c.tier("iron").name="", "blank tier name");rejected(c->c.tier("iron").name="bad\nname","control character in name");
        rejected(c->c.tier("iron").slots=44,"non-row tier capacity");rejected(c->c.tier("netherite").slots=441,"oversized tier");
        rejected(c->c.tier("iron").upgrades=9,"upgrade slot limit");rejected(c->c.tier("iron").cost=0,"zero material count");rejected(c->c.tier("iron").cost=9,"crafting-grid material count");
        rejected(c->c.tier("starter").optional=true,"optional starter");rejected(c->c.tier("starter").parent="iron","starter predecessor");
        rejected(c->c.tier("iron").parent="missing","missing predecessor");
        rejected(c->c.tier("iron").parent="iron","self predecessor cycle");
        rejected(c->c.tier("iron").slots=18,"capacity decreasing progression");
        rejected(c->c.tier("iron").upgrades=0,"upgrade decreasing progression");
        Rules.Config optional=defaults.copy();optional.materials.put("not_installed:ingot",false);optional.tiers.add(new Rules.Tier("test","Optional","netherite","not_installed:ingot",126,5,8,true));Rules.validate(optional);assertions++;
        Rules.Config beta=Rules.preset(true,defaults);Rules.validate(beta);check(beta.tier("iron").slots==defaults.tier("iron").slots+18,"roomier preset");check(!beta.compatibility,"preset does not activate compatibility");
        Rules.validate(Rules.preset(false,beta));assertions++;
        Path dir=Files.createTempDirectory("anya-storage-core-");
        try{
            Path file=dir.resolve("rules.json");String first=Rules.JSON.toJson(defaults),second=Rules.JSON.toJson(beta);
            Rules.writeAtomic(file,first);check(Files.readString(file).equals(first),"initial persisted content");
            Rules.writeAtomic(file,second);check(Files.readString(file).equals(second),"atomic replacement content");
            check(Files.readString(file.resolveSibling("rules.json.bak")).equals(first),"previous-file backup");
            try(var files=Files.list(dir)){check(files.noneMatch(p->p.toString().endsWith(".tmp")),"temporary file cleanup");}
            Rules.Config restored=Rules.JSON.fromJson(Files.readString(file),Rules.Config.class);Rules.validate(restored);check(restored.tier("iron").slots==beta.tier("iron").slots,"persisted tier round trip");
            Path impossible=dir.resolve("directory");Files.createDirectory(impossible);Files.writeString(impossible.resolve("keep"),"preserve");
            try{Rules.writeAtomic(impossible,"bad");throw new AssertionError("Unexpected replacement of a nonempty directory");}catch(java.io.IOException expected){check(Files.readString(impossible.resolve("keep")).equals("preserve"),"failed write retains existing directory content");}
        }finally{try(var paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
        String report="Configuration-core assertions passed: "+assertions+"\n";
        System.out.print(report);Path reportFile=Path.of("build/test-results/core-tests.txt");Files.createDirectories(reportFile.getParent());Files.writeString(reportFile,report);
    }
}
