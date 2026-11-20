package dev.anya.storage;

import java.util.*;

/** Bounded server policy. Existing schema-2 configuration files receive these defaults. */
public final class ExpansionRules {
    public static final class Options {
        public boolean tutorialGrant=true, tutorialRecovery=true;
        public boolean activityLogging=false, activityActorIds=false, activityMemberLogs=false;
        public int activityLogEntries=64;
        public boolean elementalEffects=true, wearableModels=true, cosmeticParticles=true;
        public double stoneArmor=2, diamondArmor=4, magmaReduction=.20, waterSwimBonus=.10, netheriteKnockback=.025;
        public boolean monsterBackpacks=false;
        public double monsterChance=.015, monsterDropChance=.25;
        public List<String> monsterSpecies=new ArrayList<>(List.of("minecraft:zombie","minecraft:husk","minecraft:skeleton","minecraft:stray"));
        public boolean imagesEnabled=false, imageUploadsForAll=false, imageSharing=false, imageModeration=true;
        public List<String> imageUploaders=new ArrayList<>();
        public int imagesPerPlayer=4, imagesTotal=128, imageWorldMiB=16;
        public String pocketCoreMaterial="minecraft:diamond";
        public int pocketSessionTicks=6000;
        public boolean pocketEditing=false;
        public boolean pocketStorage=false, pocketActivityLog=false, pocketLogNames=false;
        public int pocketLimit=64, activePocketLimit=4, pocketContainers=32, pocketLogEntries=100;
        /** Percentage of newly opened storage menus that show Cupid before the inventory. */
        public int tabletOpeningChancePercent=24;

        boolean rollTabletOpening(java.util.random.RandomGenerator random) {
            return random.nextInt(100) < tabletOpeningChancePercent;
        }
    }
    public static Options get(){return Config.get().expansion;}
    public static void validate(Options o){validate(o,false);}
    public static void validate(Options o,boolean dormantLoad){
        require(o!=null,"Expansion settings cannot be null");
        range(o.tabletOpeningChancePercent,0,100,"Tablet opening chance (%)");
        range(o.activityLogEntries,1,128,"Base activity log entries");
        require(dormantLoad||!o.pocketStorage||Boolean.getBoolean("anyastorage.pocketPrototype"),"Pocket storage remains experimental: requires the explicit disposable-test JVM gate anyastorage.pocketPrototype");
        require(dormantLoad||!o.pocketEditing||(o.pocketStorage&&Boolean.getBoolean("anyastorage.pocketPrototype")),"Room editing requires both pocketStorage and the disposable-test JVM gate");
        require(Config.validId(o.pocketCoreMaterial),"Pocket core material must be a registry ID");
        range(o.pocketSessionTicks,200,12000,"Pocket session ticks");
        finite(o.stoneArmor,0,4,"Stone armor");finite(o.diamondArmor,0,6,"Diamond armor");
        finite(o.magmaReduction,0,.5,"Magma reduction");finite(o.waterSwimBonus,0,.25,"Water swim bonus");
        finite(o.netheriteKnockback,0,.1,"High-tier knockback resistance");
        finite(o.monsterChance,0,.10,"Monster chance");finite(o.monsterDropChance,0,1,"Monster drop chance");
        require(o.monsterSpecies!=null&&o.monsterSpecies.size()<=32&&o.monsterSpecies.stream().allMatch(Config::validId),"At most 32 valid monster registry IDs");
        require(o.imageUploaders!=null&&o.imageUploaders.size()<=128,"At most 128 upload-permission UUIDs");
        for(String s:o.imageUploaders)try{UUID.fromString(s);}catch(Exception e){throw new IllegalArgumentException("Uploader permissions require full UUIDs");}
        range(o.imagesPerPlayer,1,8,"Images per player");range(o.imagesTotal,1,512,"World image count");range(o.imageWorldMiB,1,64,"Image storage MiB");
        range(o.pocketLimit,1,256,"Pocket limit");range(o.activePocketLimit,1,8,"Active pocket limit");range(o.pocketContainers,1,64,"Pocket container limit");range(o.pocketLogEntries,0,200,"Pocket log entries");
    }
    private static void require(boolean b,String s){if(!b)throw new IllegalArgumentException(s);}
    private static void finite(double d,double min,double max,String s){require(Double.isFinite(d)&&d>=min&&d<=max,s+" must be "+min+".."+max);}
    private static void range(int n,int min,int max,String s){require(n>=min&&n<=max,s+" must be "+min+".."+max);}
    private ExpansionRules(){}
}
