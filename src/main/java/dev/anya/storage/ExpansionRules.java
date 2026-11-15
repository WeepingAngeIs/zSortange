package dev.anya.storage;

import java.util.*;

/** Bounded server policy. Existing schema-2 configuration files receive these defaults. */
public final class ExpansionRules {
    public static final class Options {
        public boolean tutorialGrant=true, tutorialRecovery=true;
        public boolean elementalEffects=true, wearableModels=true, cosmeticParticles=true;
        public double stoneArmor=2, diamondArmor=4, magmaReduction=.20, waterSwimBonus=.10, netheriteKnockback=.025;
        public boolean monsterBackpacks=false;
        public double monsterChance=.015, monsterDropChance=.25;
        public List<String> monsterSpecies=new ArrayList<>(List.of("minecraft:zombie","minecraft:husk","minecraft:skeleton","minecraft:stray"));
        public boolean imagesEnabled=false, imageUploadsForAll=false, imageSharing=false, imageModeration=true;
        public List<String> imageUploaders=new ArrayList<>();
        public int imagesPerPlayer=4, imagesTotal=128, imageWorldMiB=16;
        public boolean pocketStorage=false, pocketActivityLog=false, pocketLogNames=false;
        public int pocketLimit=64, activePocketLimit=4, pocketContainers=32, pocketLogEntries=100;
    }
    public static Options get(){return Config.get().expansion;}
    public static void validate(Options o){
        require(o!=null,"Expansion settings cannot be null");
        require(!o.pocketStorage,"Pocket storage is not implemented in this milestone; its gate must stay OFF");
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
