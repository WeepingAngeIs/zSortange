package dev.anya.storage.guide;

import dev.anya.storage.*;
import dev.anya.storage.appearance.Looks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import java.util.*;

/** Rebuilt from live configuration and the server recipe manager each time the guide opens. */
public final class GuideContent {
    private static String pct(double n){return String.format(Locale.ROOT,"%.1f%%",100*n);}
    private static String num(double n){return String.format(Locale.ROOT,"%.2f",n).replaceAll("0+$","").replaceAll("\\.$","");}
    private static String clean(String s,int max){return s.replaceAll("[\\p{Cntrl}§]","").substring(0,Math.min(max,s.replaceAll("[\\p{Cntrl}§]","").length()));}
    private static void page(List<GuidePage> out,String chapter,String title,String art,String body,String note){out.add(GuidePage.text(chapter,title,art,body,note));}
    public static List<GuidePage> build(ServerLevel level){
        Config.Data d=Config.get();var o=d.expansion;List<GuidePage> out=new ArrayList<>();
        page(out,"Start here","Ancient Technology","bow_cat",
            "A field guide to beautiful bags and useful machines.\n\nYour backpack has three independent choices: ELEMENT for its abilities, SKIN for its look, and TIER for capacity and upgrade slots.\n\nChanging the tier keeps your chosen element, skin, contents, installed upgrades and custom name.\n\nUse Contents to jump to a chapter. Scroll the right-hand page when text continues. Hover recipe ingredients for their exact names.",
            "Rules snapshot: revision "+d.revision+". Close and reopen this book after an admin changes settings.");
        recipe(out,level,"Start here","Your first leather backpack","plain",
            "Craft this starter with vanilla materials. No custom ores or world generation are needed.\n\nRight-click the backpack to open storage. Click your inventory items to deposit; click stored items to withdraw. Right-click a listed stack to take one.\n\nSneak-use opens upgrades. The Wardrobe button handles wearing, elemental choices, skins and interior themes.","backpack");
        for(String species:Looks.LEAVES){
            recipe(out,level,"Leaf starters",Looks.title(species)+" leaf backpack","leaf_"+species,
                "A cheaper woodland beginning with "+species.replace('_',' ')+" leaves, leather and string. The species changes the leafy appearance, not its storage capacity.\n\nIt starts on the leaf elemental path. Leaf is a visual identity, not an extra armor or healing bonus.\n\nEquip it in the native Accessories back slot when that mod is installed, or choose Wear this backpack in Wardrobe. The same bag can advance through Iron, Gold, Diamond and Netherite without losing its identity.","leaf_backpack_"+species);
        }
        page(out,"Elements","Wearing one backpack","stone",
            "Open a backpack, press Wardrobe, then Wear this backpack. The worn model refers to that real item in your main inventory or offhand. It does not create a second backpack.\n\nOnly one selected backpack can grant elemental benefits. Duplicate copies with the same BagId disable those benefits. Dropping, losing, unequipping or dying removes them.\n\nAn armor item or Elytra is not replaced. Origins and other mods may add their own effects; this mod does not promise a combined balance guarantee.",
            "Elemental effects: "+(o.elementalEffects&&d.backpacks?"ON":"OFF on this server")+". Attributes are configurable by operators.");
        Map<String,String> abilities=new LinkedHashMap<>();
        abilities.put("leaf","Leaf is a cosmetic elemental identity. It grants no combat attribute. Its oak, cherry and other species remain distinct when you upgrade capacity.");
        abilities.put("stone","While worn, this path adds "+num(o.stoneArmor)+" armor points. This is armor, not extra hearts. It does not add armor toughness.");
        abilities.put("diamond","While worn, this path adds "+num(o.diamondArmor)+" armor points. It is independent of the Diamond storage tier: a Diamond-tier leaf backpack is still a leaf backpack.");
        abilities.put("magma","While worn, incoming damage tagged as fire is reduced by "+pct(o.magmaReduction)+". This is not immunity. Lava and fire can still hurt, and the backpack does not stop ignition.");
        abilities.put("water","While worn and in water, swim speed increases by "+pct(o.waterSwimBonus)+". There is no water breathing, infinite air, underwater mining bonus or drowning immunity.");
        for(var e:abilities.entrySet()){
            String name=e.getKey();recipe(out,level,"Elements",Looks.title(name)+" binding",name.equals("leaf")?"leaf_oak":name,
                e.getValue()+"\n\nCraft the binding shown, then combine ONE backpack with ONE binding in a crafting grid. This unlocks the element on that bag. Switch among unlocked elements in Wardrobe without paying again.\n\n"+(o.elementalEffects&&d.backpacks?"Elemental benefits are enabled.":"Elemental benefits are currently disabled by the server; the look remains available."),"element_"+name);
        }
        page(out,"Progression","Capacity is not appearance","leaf_cherry",
            "Storage progression is separate from element and skin. Upgrades preserve the BagId, stored stacks, names, components, installed upgrades and interior selection.\n\nExisting backpacks retain their saved capacity when an operator reduces a tier's settings. Nothing is silently truncated.\n\nThe following pages show THIS server's tier materials, predecessor and costs. Dynamic tier recipes are previewed here and in settings; they are not a dedicated EMI or JEI integration.","A favorite cherry or cat skin stays with you through Netherite and beyond.");
        for(Config.Tier t:d.tiers){
            String reason=Config.unavailable(d,t);String pred=t.id.equals("starter")?"":Config.predecessor(d,t);Config.Tier predecessor=d.tier(pred);
            String body="Capacity: "+t.slots+" slots.\nUpgrade slots: "+t.upgrades+".\n\n";
            List<GuidePage.Cell> cells=new ArrayList<>();String heading="";
            if(t.id.equals("starter"))body+="This is the entry tier for both leather and leaf starters. Use the starter recipe pages for the exact crafting patterns.";
            else{
                body+="Predecessor: "+(predecessor==null?pred:predecessor.name)+".\n\nUse one predecessor backpack and "+t.cost+" SEPARATE ingredient cells containing "+t.material+". Each cell consumes one material item. A stack of materials in just one cell does not meet a multi-cell cost.\n\nThe arrangement is shapeless. Keep the backpack's contents inside: the upgrade preserves them.";
                cells.add(new GuidePage.Cell("anyastorage:backpack",clean((predecessor==null?pred:predecessor.name)+" predecessor backpack",128)));
                for(int i=0;i<t.cost;i++)cells.add(cell(t.material));heading="Shapeless tier upgrade: 1 bag + "+t.cost+" material cells";
            }
            if(t.id.equals("netherite"))body+="\n\nThis tier and configured descendants can add "+num(o.netheriteKnockback)+" knockback resistance while worn, when elemental effects are enabled.";
            String status=reason.isEmpty()?"AVAILABLE under the current server rules.":"UNAVAILABLE: "+clean(reason,230);
            out.add(new GuidePage("Progression",clean(t.name+" storage tier",64),t.id.equals("diamond")?"diamond":t.id.equals("netherite")?"futuristic":"plain",clean(body,2048),clean(status,256),heading,cells));
        }
        page(out,"Progression","Beyond Netherite","futuristic",
            "Dragonsteel, Witherite and other material paths are OPTIONAL. The server must have the actual registered ingredient, and the operator must enable compatibility and the tier.\n\nUse /anya storage: Compatibility / Tiers. Add Held Material reads the exact installed item instead of guessing its registry ID. Configure the predecessor, cost, capacity and slots, then Review and Apply.\n\nThis mod consumes the material in backpack upgrades. It does not manufacture Dragonsteel or Witherite ingots. Disabled or absent materials do not become free substitutes.","Only configured tiers appear in this book. No Ice and Fire or Cataclysm dependency is bundled.");
        page(out,"Wardrobe","Your look, your choice","bow_cat",
            "Craft a skin patch, then combine one patch with one backpack. This unlocks and applies that skin on that bag. Return to Wardrobe to switch between already unlocked looks for free.\n\nThe Elemental skin follows the current element and leaf species. Other skins override the appearance only: a bow-cat bag can still have the stone path.\n\nInterior themes are separate again. Cycle Atelier, Botanical, Midnight, Galaxy, Circuit and Ocean in Wardrobe without paying materials.","The cat designs are original bow-cat themes, not official licensed character merchandise.");
        for(String skin:Looks.SKINS)if(!skin.equals("elemental"))recipe(out,level,"Wardrobe",Looks.title(skin)+" skin",skin,
            "Craft the skin patch shown, then combine it with one backpack. This changes the appearance without changing tier, capacity, elemental bonuses or stored items.\n\nThe patch is consumed once. Its unlocked look is saved on the bag and can be selected again for free.\n\nParticles are cosmetic only. Walking leaf styles shed restrained leaves; Galaxy twinkles. Client controls can hide models, particles or private interiors.","skin_"+skin);
        Map<String,String> upgrades=new LinkedHashMap<>();
        upgrades.put("pickup","Moves normally picked-up matching items from your inventory into the bag using its filter. It is not a ground-vacuum or long-distance magnet. Protect important inventory slots first.");
        upgrades.put("feeding","Uses supported plain foods from the bag when they fit the missing hunger. It does not eat every modded food or consume named/modified food indiscriminately.");
        upgrades.put("refill","Tops up existing matching stacks from the bag. It honors protected slots and exact item components; it is not an unrestricted item-creation feature.");
        upgrades.put("crafting","The explicit crafting button opens a portable crafting table. It uses your inventory; it is not automatic crafting across a storage network.");
        upgrades.put("smelting","Smelts explicitly filtered inputs using real server smelting recipes and plain coal or charcoal. It requires output space and preserves recipe remainders. It does not supply free furnace fuel.");
        upgrades.put("compacting","Press the compacting button to perform one supported vanilla 3-by-3 compression. It is not continuous autocrafting. Named or modified material stacks are not compacted.");
        upgrades.put("resupply","Select an empty hotbar slot, then press the resupply button. It transfers a tool or sword from the backpack. It does not overwrite the item in your hand.");
        for(var e:upgrades.entrySet())recipe(out,level,"Upgrades",Looks.title(e.getKey())+" upgrade","item:anyastorage:"+e.getKey()+"_upgrade",
            e.getValue()+"\n\nSneak-use a backpack, carry the crafted upgrade and left-click its icon to install. Right-click the icon to recover the exact installed upgrade item when there is inventory space.\n\nThis server's upgrade switch: "+(Boolean.TRUE.equals(d.upgrades.get(e.getKey()))?"ON":"OFF")+". Automated upgrades pause while a storage menu is open.",e.getKey()+"_upgrade");
        recipe(out,level,"Home storage","Build a home hub","item:anyastorage:hub",
            "Place a hub near physical chests or barrels. Its initial radius is "+d.initialRadius+" blocks; the maximum is "+d.maxRadius+". The same three-dimensional radius governs remote access and connected storage.\n\nStorage is not copied into the hub. Search results point to real stacks in loaded, permitted containers. Discovery is incremental; a new chest may take a moment to appear.\n\nNo storage chunk is force-loaded by this base network.","hub");
        recipe(out,level,"Home storage","Bind the remote","item:anyastorage:remote",
            "Sneak-right-click the hub with a remote to bind it. Right-click the remote within that hub's radius to search storage. A different dimension or out-of-range use is denied.\n\nThe input searches names, registry IDs, @mod namespaces and #tags. Middle-click a listed stack to favorite its item type.\n\nThe remote is your everyday interface. /anya storage is for settings, not a replacement for the remote.","remote");
        recipe(out,level,"Home storage","Labelled storage chest","item:anyastorage:chest",
            "This chest holds 54 ordinary inventory slots. Its front sticker splits across the base and hinged lid. Available symbols include hearts, stars, leaves and gears.\n\nSneak-use a bound remote on a supported chest inside your hub's radius to set category, exact-item filters, destination priority and overflow.\n\nThe owner controls the profile. Claim and lock checks still apply; trust does not bypass Open Parties and Claims.","chest");
        page(out,"Start here","Slots, scrolling and your back","leaf_oak",
            "The storage grid represents real physical slots, including empty ones. Its counter shows occupied and total capacity. The mouse wheel and scrollbar move through larger bags without changing item positions. Search shows matching occupied slots only. Controls live to the right, cosmetics to the left.\n\nOpen Anya Backpack is configurable in Controls. New installations suggest B; saved settings are preserved. A conflicting key is warned about and Anya suppresses its action until rebound.\n\nOpen priority: native Accessories back slot, main hand, selected inventory bag, main inventory order, offhand. Only one bag grants worn effects.","Native back-slot support is tested against Accessories 1.1.0-beta.53. Move the bag to main inventory to install/remove upgrades. Crafting bindings are unchanged.");
        page(out,"Home storage","Share a base safely","item:minecraft:iron_door",
            "In Base Profile, open Member Permissions. View, Deposit, Withdraw and Read Activity Log are separate. Existing trusted members keep their previous hub access; newly added members start with View only.\n\nWithout View, no storage stacks are sent to the menu. Deposit-only members can click their own inventory to deposit. QuickStack and Dump need View + Deposit. Withdrawal needs View + Withdraw; Organize needs all three.\n\nThese permissions govern this hub interface, not physical entry into an ordinary base. Locks and claims still control direct chests. Pocket visit permissions are not implemented yet.","Permission changes invalidate queued hub transfers. Ownership and OPAC checks remain mandatory.");
        page(out,"Home storage","Activity without private item data","item:minecraft:writable_book",
            "Base Profile contains a bounded Activity Log of successful hub deposits and withdrawals. Current recording: "+(o.activityLogging?"ON":"OFF")+". The server keeps at most "+o.activityLogEntries+" recent entries per base.\n\nDefault logging is off. When enabled, events record UTC time, operation and item count. Actor UUID collection requires a separate opt-in. Custom item names, components, image IDs and contents are never recorded.\n\nOwners can read or clear their base log. Members need both the server sharing policy and explicit Log permission. This is an activity summary, not a crash-recovery journal.","Direct chest operations and other mods are not captured. Server administrators and backups can retain saved records.");
        page(out,"Home storage","Feed, preview, expand","item:minecraft:diamond",
            "In Base Profile, select an inventory resource to preview feeding it. The preview shows the consumed amount, points and the next radius upgrade cost.\n\nFeeding stores progress. The separate Upgrade Radius button spends the displayed cost. Current radius step: "+d.radiusStep+" blocks.\n\nOperators can edit resource values and limits. Filled containers and backpacks cannot be burned as feeder fuel. A smaller configured range hides distant storage; it does not delete its items.","Use the live feeder preview for costs. This book never assumes an ore value that an operator may have changed.");
        page(out,"Home storage","QuickStack without surprises","item:minecraft:hopper",
            "QuickStack deposits only where an identical item-and-component match already exists. Dump accepts broader item-type matches and designated destinations; it does not empty unrelated items into random chests.\n\nHotbar slots are protected by default. Armor and offhand are never swept. /anya slots changes protection; shift-click a slot there explains why QuickStack would accept or skip it.\n\nOrganize first shows a preview. Confirm separately to move items. Assign QuickStack, Dump and Open Backpack keys in Minecraft Controls.","Manual deposit clicks are explicit actions; protected-slot rules apply to automatic deposits.");
        page(out,"Private interiors","Make the lining yours","bow_cat",
            "Custom images are a NEW optional server feature. Current uploads: "+(o.imagesEnabled?"enabled":"disabled")+". Operators control who may upload.\n\nOpen Wardrobe and drag exactly one local PNG onto the game window. Use non-interlaced 8-bit RGB or RGBA, 32 to 256 pixels on each side, at most 64 KiB. JPEG, GIF, APNG, URLs and file-path commands are not accepted.\n\nThe server validates and re-encodes the image without metadata. Built-in interior art remains available when an upload is rejected or disabled.","Image data is stored in this world on the server, not inside the backpack item.");
        page(out,"Private interiors","Privacy and moderation","midnight_cat",
            "Images are owner-only by default, even before moderation approval. Sharing requires the server sharing switch, approval, and an explicit viewer UUID. Merely holding a bag does not grant image access.\n\nOperators can inspect, reject and delete uploads in the moderation library. Owners can revoke viewers or delete images. The client keeps downloaded images in memory only and releases them on close/disconnect.\n\nAnyone permitted to view an image can still capture it. Revocation cannot erase screenshots or copies already saved elsewhere.","Current quotas: "+o.imagesPerPlayer+" images/player, "+o.imagesTotal+" images/world, "+o.imageWorldMiB+" MiB/world. One upload/player/minute.");
        page(out,"Field notes","Backpacks on monsters","spooky",
            "The optional monster-backpack feature is "+(o.monsterBackpacks?"ON":"OFF")+". Eligible natural or chunk-generation spawns have a "+pct(o.monsterChance)+" selection chance, then a "+pct(o.monsterDropChance)+" player-kill drop chance.\n\nThe decorative bag marker does not replace armor or copy a player inventory. A successful reward creates a fresh, empty starter backpack with its own identity.\n\nNamed mobs, pre-existing custom data, equipment and ineligible spawn types are skipped. Full-pack Apotheosis interaction still needs acceptance testing.","No protected inventory contents are placed in a monster reward.");
        page(out,"Field notes","What this milestone does not do","futuristic",
            "Pocket dimensions, pocket visit permissions, pocket activity logs and live portal rendering are NOT implemented in this milestone. Separate permissions and activity logging for ordinary home hubs are available as described in Home Storage. The experimental pocket gate cannot be enabled.\n\nDo not remove the old storage mods yet. Test this build in a disposable or backed-up world.\n\nCarry On, Origins balance, Open Parties and Claims, full-pack shaders, simultaneous clients and forced-disconnect behavior still require testing with your actual pack.","This book describes implemented features, not a promise that the future pocket system already exists.");
        recipe(out,level,"Field notes","Recover this book","item:anyastorage:ancient_technology",
            "Automatic first-login grant: "+(o.tutorialGrant?"ON":"OFF")+". It is recorded per player, not tied to keeping the book. Death, respawn, reconnect and ordinary restart do not grant another copy. A full inventory defers the grant instead of dropping it.\n\nLost your copy? /anya guide recover consumes one plain Book. The recovery recipe below is another option when enabled.\n\nThe grant marker is committed before delivery. A crash at that exact boundary may miss the item rather than duplicate it; explicit recovery handles that case.","ancient_technology");
        if(out.size()>128)throw new IllegalStateException("Guide page budget exceeded");return List.copyOf(out);
    }
    private static GuidePage.Cell cell(String id){
        ResourceLocation key=ResourceLocation.tryParse(id);Item item=key==null?Items.AIR:BuiltInRegistries.ITEM.get(key);
        return new GuidePage.Cell(clean(id,128),clean(item==Items.AIR?id:new ItemStack(item).getHoverName().getString(),128));
    }
    private static void recipe(List<GuidePage> out,ServerLevel level,String chapter,String title,String art,String body,String id){
        var holder=level.getRecipeManager().byKey(ExpansionPackets.id(id));List<GuidePage.Cell> cells=new ArrayList<>();String heading="",note;
        if(holder.isEmpty()){note="RECIPE UNAVAILABLE under the current server rules or data packs.";}
        else{
            var r=holder.get().value();var ingredients=r.getIngredients();int w=r instanceof ShapedRecipe shaped?shaped.getWidth():3;
            for(int i=0;i<9;i++)cells.add(new GuidePage.Cell("",""));
            for(int i=0;i<ingredients.size()&&i<9;i++){
                ItemStack[] choices=ingredients.get(i).getItems();if(choices.length==0)continue;
                int slot=r instanceof ShapedRecipe?(i/w)*3+i%w:i;if(slot>=9)break;
                ItemStack sample=choices[0];String name=sample.getHoverName().getString()+(choices.length>1?" (or another accepted ingredient)":"");
                cells.set(slot,new GuidePage.Cell(BuiltInRegistries.ITEM.getKey(sample.getItem()).toString(),clean(name,128)));
            }
            ItemStack result=r.getResultItem(level.registryAccess());heading=(r instanceof ShapedRecipe?"Shaped":"Shapeless")+" recipe: "+(result.isEmpty()?"special result":result.getCount()+" result");
            note="Read from server recipe anyastorage:"+id+". Hover ingredients for names.";
        }
        out.add(new GuidePage(chapter,title,art,body,clean(note,256),heading,cells));
    }
    public static List<GuidePage> renderFixture(){
        // Explicit development illustration only; production always receives a real server snapshot.
        return List.of(GuidePage.text("Client render check","Ancient Technology","bow_cat","Every journey begins with a well-made bag.\n\nChoose an element for abilities, a skin for its look, and a tier for storage. Your favorite appearance survives capacity upgrades.\n\nOpen Contents for elemental paths, exact server recipes, upgrade instructions and image privacy controls.","Development render fixture. Production pages read the actual server configuration."),
            new GuidePage("Client render check","Stone binding","stone","A sturdy elemental path. The default stone bonus is two armor points while worn.\n\nCraft a binding, then combine it with one backpack. The bag keeps its tier, name and stored contents.\n\nThe actual in-game guide uses the server's current armor setting, including disabled effects.","Development recipe fixture, not a live-server claim.","Stone binding example",List.of(new GuidePage.Cell("",""),cell("minecraft:cobblestone"),new GuidePage.Cell("",""),cell("minecraft:cobblestone"),cell("minecraft:string"),cell("minecraft:cobblestone"),new GuidePage.Cell("",""),cell("minecraft:cobblestone"),new GuidePage.Cell("",""))));
    }
    private GuideContent(){}
}
