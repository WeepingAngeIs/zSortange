package dev.anya.storage;

public final class CoreTests {
    private static int checks;
    private static void check(boolean value,String why) { checks++; if(!value) throw new AssertionError(why); }
    private static void invalid(Config.Data d,String why) { boolean failed=false; try { Config.validate(d); } catch(IllegalArgumentException e) { failed=true; } check(failed,why); }
    public static void main(String[] args) {
        Config.Data d=new Config.Data(); Config.validate(d); check(d.tiers.size()==5,"vanilla tiers");
        Config.Data copy=Config.copy(d); copy.maxRadius=48; check(d.maxRadius==64,"draft isolation");
        check(!d.compatibility && d.cubeRecipe==0 && !d.recipes.get("endgame"),"safe defaults");
        copy=Config.copy(d); copy.tier("iron").slots=9; invalid(copy,"no capacity downgrade");
        copy=Config.copy(d); copy.tier("iron").predecessor="gold"; invalid(copy,"no cycle");
        copy=Config.copy(d); copy.tier("gold").predecessor="absent"; invalid(copy,"no missing predecessor");
        copy=Config.copy(d); copy.tiers.add(copy.tier("iron")); invalid(copy,"no duplicate tiers");
        copy=Config.copy(d); copy.tier("iron").material="invalid"; invalid(copy,"exact registry ID");
        copy=Config.copy(d); copy.tier("iron").cost=9; invalid(copy,"3x3 recipe bounds");
        copy=Config.copy(d); copy.tier("iron").slots=217; invalid(copy,"bounded backpack size");
        copy=Config.copy(d); copy.maxRadius=129; invalid(copy,"bounded scan radius");
        copy=Config.copy(d); copy.recipes.remove("hubs"); invalid(copy,"recipe schema");
        for(int radius=4;radius<=128;radius++) { copy=Config.copy(d); copy.initialRadius=4;copy.maxRadius=radius;Config.validate(copy);check(true,"valid radii"); }
        for(int i=1;i<200;i++) { copy=Config.copy(d); copy.quickRadius=-i;invalid(copy,"negative radius"); }
        check(Config.validId("iceandfire:dragonsteel_fire_ingot"),"ID syntax only, not a claim this item is installed");
        check(!Config.validId("Minecraft:Diamond"),"lowercase registry paths");
        System.out.println("PASS: "+checks+" configuration assertions");
    }
}
