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
                e.getValue()+"\n\nRight-click your placed backpack with this binding, or combine the binding and backpack in a crafting grid. This unlocks the element on that bag. Switch among unlocked elements in Wardrobe without paying again.\n\n"+(o.elementalEffects&&d.backpacks?"Elemental benefits are enabled.":"Elemental benefits are currently disabled by the server; the look remains available."),"element_"+name);
        }
        page(out,"Progression","Capacity is not appearance","leaf_cherry",
            "Your backpack name shows its current look and storage tier. Anvil names take priority. To change tiers directly, set your backpack down and right-click it with the full cost of the next tier in one hand. Your stored items and installed upgrades stay inside.\n\nExisting backpacks retain their saved capacity when an operator reduces a tier's settings. Nothing is silently truncated.\n\nThe following pages show THIS server's tier materials, predecessor and costs. Dynamic tier recipes are previewed here and in settings; they are not a dedicated EMI or JEI integration.","A favorite cherry or cat skin stays with you through Netherite and beyond.");
        for(Config.Tier t:d.tiers){
            String reason=Config.unavailable(d,t);String pred=t.id.equals("starter")?"":Config.predecessor(d,t);Config.Tier predecessor=d.tier(pred);
            String body="Capacity: "+t.slots+" slots.\nUpgrade slots: "+t.upgrades+".\n\n";
            List<GuidePage.Cell> cells=new ArrayList<>();String heading="";
            if(t.id.equals("starter"))body+="This is the entry tier for both leather and leaf starters. Use the starter recipe pages for the exact crafting patterns.";
            else{
                body+="Predecessor: "+(predecessor==null?pred:predecessor.name)+".\n\nUse one predecessor backpack and "+t.cost+" SEPARATE ingredient cells containing "+t.material+". Each cell consumes one material item. A stack of materials in just one cell does not meet a multi-cell cost.\n\nFor a placed bag, just right-click it with the full material cost in hand. Your stored items stay inside.";
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
            "Right-click your placed backpack with a skin patch, or combine it with the bag in a crafting grid. This unlocks and applies that skin on that bag. Return to Wardrobe to switch between already unlocked looks for free.\n\nThe Elemental skin follows the current element and leaf species. Other skins override the appearance only: a bow-cat bag can still have the stone path.\n\nInterior themes are separate again. Cycle Atelier, Botanical, Midnight, Galaxy, Circuit and Ocean in Wardrobe without paying materials.","The cat designs are original bow-cat themes, not official licensed character merchandise.");
        for(String skin:Looks.SKINS)if(!skin.equals("elemental"))recipe(out,level,"Wardrobe",Looks.title(skin)+" skin",skin,
            "Apply this patch by right-clicking your placed backpack or by combining it with the bag in a crafting grid. This changes the appearance without changing tier, capacity, elemental bonuses or stored items.\n\nThe patch is consumed once. Its unlocked look is saved on the bag and can be selected again for free.\n\nParticles are cosmetic only. Walking leaf styles shed restrained leaves; Galaxy twinkles. Client controls can hide models, particles or private interiors.","skin_"+skin);
        Map<String,String> upgrades=new LinkedHashMap<>();
        upgrades.put("pickup","Moves normally picked-up matching items from your inventory into the bag using its filter. It is not a ground-vacuum or long-distance magnet. Protect important inventory slots first.");
        upgrades.put("feeding","Uses supported plain foods from the bag when they fit the missing hunger. It does not eat every modded food or consume named/modified food indiscriminately.");
        upgrades.put("advanced_feeding","Craft a Feeding Upgrade with one clock and one bowl. In Upgrades, choose how many hunger points must be missing before eating. Select plain foods from your inventory and add them in the order you prefer. A listed food is skipped if absent or too filling. With an empty list, the bag serves its first suitable food. Carry the bag with menus closed. Supported foods are apple, beetroot, carrot, baked potato, bread, and cooked beef, pork, chicken, mutton, rabbit, cod and salmon.");
        upgrades.put("refill","Tops up existing matching stacks from the bag. It honors protected slots and exact item components; it is not an unrestricted item-creation feature.");
        upgrades.put("crafting","The explicit crafting button opens a portable crafting table. It uses your inventory; it is not automatic crafting across a storage network.");
        upgrades.put("smelting","The original Smelting Upgrade automatically cooks the first allowed input it finds in bag slot order, using server furnace recipes and plain coal or charcoal. It requires output space and preserves recipe remainders. It does not supply free furnace fuel.");
        upgrades.put("manual_smelting","In Upgrades, add allowed input IDs to the smelting filter. Put matching items and plain coal or charcoal in the bag, then start one cook. Carry the bag with menus closed while it cooks. Start another cook when you want another result.");
        upgrades.put("auto_smelting","Craft the manual disc with a clock and a powered rail. Add input IDs to the smelting filter in the order you want them cooked. Carry the bag with matching inputs and plain coal or charcoal inside. With menus closed, it keeps cooking and chooses the first available input in filter order.");
        upgrades.put("manual_blasting","Put an input for a blast furnace recipe in your bag with plain coal or charcoal. Add its item ID to the blasting filter in Upgrades, then start one cook. Carry the bag with menus closed until it finishes.");
        upgrades.put("auto_blasting","Craft the manual blast furnace disc with a clock and obsidian. Add allowed input IDs to the blasting filter in priority order. With matching inputs and plain coal or charcoal in the carried bag, it runs blast furnace recipes continuously while menus are closed.");
        upgrades.put("manual_smoking","Put an input for a smoker recipe in your bag with plain coal or charcoal. Add its item ID to the smoking filter in Upgrades, then start one cook. Carry the bag with menus closed until it finishes.");
        upgrades.put("auto_smoking","Craft the manual smoker disc with a clock and a copper grate. Add allowed input IDs to the smoking filter in priority order. With matching inputs and plain coal or charcoal in the carried bag, it runs smoker recipes continuously while menus are closed.");
        upgrades.put("compacting","Press the compacting button to perform one supported vanilla 3-by-3 compression. It is not continuous autocrafting. Named or modified material stacks are not compacted.");
        upgrades.put("resupply","Select an empty hotbar slot, then press the resupply button. It transfers a tool or sword from the backpack. It does not overwrite the item in your hand.");
        for(var e:upgrades.entrySet())recipe(out,level,"Upgrades",switch(e.getKey()){
            case "manual_smelting" -> "Manual Smelter Upgrade";
            case "auto_smelting" -> "Auto Smelter Upgrade";
            case "manual_smoking" -> "Manual Smoker Upgrade";
            case "advanced_feeding" -> "Advanced Feeding Upgrade";
            case "auto_smoking" -> "Auto Smoker Upgrade";
            case "manual_blasting" -> "Manual Blast Furnace Upgrade";
            case "auto_blasting" -> "Auto Blast Furnace Upgrade";
            default -> Looks.title(e.getKey())+" upgrade";
        },"item:anyastorage:"+e.getKey()+"_upgrade",
            e.getValue()+"\n\nSneak-use a backpack, carry the crafted upgrade and left-click its icon to install. Right-click the icon to recover the exact installed upgrade item when there is inventory space.\n\nThis server's upgrade switch: "+(Boolean.TRUE.equals(d.upgrades.get(Config.upgradeFamily(e.getKey())))?"ON":"OFF")+". Automated upgrades pause while a storage menu is open.",e.getKey()+"_upgrade");
        recipe(out,level,"Home storage","Build a home hub","item:anyastorage:hub",
            "Place a hub near physical chests or barrels. Its initial radius is "+d.initialRadius+" blocks; the maximum is "+d.maxRadius+". That radius governs regular tablet access and which physical stores the hub includes.\n\nStorage is not copied into the hub. Search results point to real stacks in loaded, permitted containers. Discovery is incremental; a new chest may take a moment to appear.\n\nNo storage chunk is force-loaded by this base network.","hub");
        recipe(out,level,"Home storage","Bind a Cupid Tablet","item:anyastorage:cupid_tablet",
            "Craft a Cupid Tablet and sneak-right-click your hub to bind it. Use the tablet in air within that hub's radius to open connected storage. Search names, registry IDs, @mod namespaces or #tags. Middle-click a stack to favorite it.\n\nAn old heart Remote becomes a Cupid Tablet when you carry or use it. It remembers the same hub and pocket.","cupid_tablet");
        page(out,"Home storage","UwU Tablet","item:anyastorage:creative_tablet",
            "An UwU Tablet works in Survival. You can find it in the Anya Storage creative tab. Sneak-use it at a permitted hub to bind it, then use it in air from any distance or dimension. To link your pocket instead, sneak-use the bound tablet on its Skygrid Cube.",
            "The hub owner controls who can view, deposit and withdraw. Claims and container locks still apply.");
        String cubeReason = cubeUnavailable(level, d);
        String dragonCost = switch (d.cubeRecipe) {
            case 1 -> "four Dragon's Breath";
            case 2 -> "four Dragon Victory Tokens";
            default -> "four Dragon's Breath or four Dragon Victory Tokens, depending on the chosen mode";
        };
        String cubeCost = "one Storage Core, four Nether Stars, and " + dragonCost;
        String cubeStatus = cubeReason.isEmpty() ? "Cube crafting is enabled." : "Cube crafting is unavailable. " + cubeReason;
        recipe(out,level,"Home storage","Labelled Storage Chest","item:anyastorage:chest",
            "Start with 54 slots in a Labelled Storage Chest. The next tier is the 216-slot UwU Storage Cube. " + cubeStatus
            + "\n\nCube crafting takes " + cubeCost + ". Turn to UwU Storage Cube for the crafting grid, then to Storage Core for its shaped recipe."
            + "\n\nA hub automatically discovers both placed containers when they are loaded, within range and accessible to you."
            + "\n\nThe chest's front sticker splits across the base and hinged lid. Its symbols include hearts, stars, leaves and gears."
            + "\n\nSneak-use a bound tablet on a supported chest inside your hub's radius to set category, exact-item filters, priority and overflow. The owner controls routing. Claim and lock checks still apply.","chest");
        cubePage(out, d.cubeRecipe, cubeCost, cubeReason);
        recipe(out,level,"Home storage","Storage Core","item:anyastorage:storage_core",
            "Craft a Storage Core for the UwU Storage Cube. The standard shaped recipe uses eight Diamond Blocks around one Netherite Block."
            + "\n\nThe grid shows the recipe loaded on this server. The Endgame recipe group must be enabled for the standard recipe.","storage_core");
        page(out,"Start here","Slots, scrolling and your back","leaf_oak",
            "The storage grid represents real physical slots, including empty ones. Its counter shows occupied and total capacity. The mouse wheel and scrollbar move through larger bags without changing item positions. Search shows matching occupied slots only. Controls live to the right, cosmetics to the left.\n\nOpen Anya Backpack is configurable in Controls. New installations suggest B; saved settings are preserved. A conflicting key is warned about and Anya suppresses its action until rebound.\n\nOpen priority: native Accessories back slot, main hand, selected inventory bag, main inventory order, offhand. Only one bag grants worn effects.","Native back-slot support is tested against Accessories 1.1.0-beta.53. Move the bag to main inventory to install/remove upgrades. Crafting bindings are unchanged.");
        page(out,"Home storage","Share a base safely","item:minecraft:iron_door",
            "In Base Profile, open Member Permissions. View, Deposit, Withdraw and Read Activity Log are separate. Existing trusted members keep their previous hub access; newly added members start with View only.\n\nWithout View, no storage stacks are sent to the menu. Deposit-only members can click their own inventory to deposit. QuickStack and Dump need View + Deposit. Withdrawal needs View + Withdraw; Organize needs all three.\n\nThese permissions govern this hub interface, not physical entry into an ordinary base. Locks and claims still control direct chests. The new Visit right applies only to the gated experimental pocket entrance; old trust does not grant Visit.","Permission changes invalidate queued hub transfers. Ownership and OPAC checks remain mandatory.");
        page(out,"Home storage","Activity without private item data","item:minecraft:writable_book",
            "Base Profile contains a bounded Activity Log of successful hub deposits and withdrawals. Current recording: "+(o.activityLogging?"ON":"OFF")+". The server keeps at most "+o.activityLogEntries+" recent entries per base.\n\nDefault logging is off. When enabled, events record UTC time, operation and item count. Actor UUID collection requires a separate opt-in. Custom item names, components, image IDs and contents are never recorded.\n\nOwners can read or clear their base log. Members need both the server sharing policy and explicit Log permission. This is an activity summary, not a crash-recovery journal.","Direct chest operations and other mods are not captured. Server administrators and backups can retain saved records.");
        page(out,"Home storage","Feed, preview, expand","item:minecraft:diamond",
            "In Base Profile, select an inventory resource to preview feeding it. The preview shows the consumed amount, points and the next radius upgrade cost.\n\nFeeding stores progress. The separate Upgrade Radius button spends the displayed cost. Current radius step: "+d.radiusStep+" blocks.\n\nOperators can edit resource values and limits. Filled containers and backpacks cannot be burned as feeder fuel. A smaller configured range hides distant storage; it does not delete its items.","Use the live feeder preview for costs. This book never assumes an ore value that an operator may have changed.");
        page(out,"Home storage","QuickStack without surprises","item:minecraft:hopper",
            "QuickStack deposits only where an identical item-and-component match already exists. Dump accepts broader item-type matches and designated destinations; it does not empty unrelated items into random chests.\n\nHotbar slots are protected by default. Armor and offhand are never swept. /anya slots changes protection; shift-click a slot there explains why QuickStack would accept or skip it.\n\nOrganize first shows a preview. Confirm separately to move items. Assign QuickStack, Dump and Open Backpack keys in Minecraft Controls.","Manual deposit clicks are explicit actions; protected-slot rules apply to automatic deposits.");
        page(out,"Private interiors","Make the lining yours","bow_cat",
            "Custom images are a NEW optional server feature. Current uploads: "+(o.imagesEnabled?"enabled":"disabled")+". Operators control who may upload.\n\nDrag one local PNG onto your backpack or its Wardrobe to apply it privately. Use non-interlaced 8-bit RGB or RGBA, 32 to 256 pixels on each side, at most 64 KiB. JPEG, GIF, APNG, URLs and file-path commands are not accepted.\n\nThe server validates and re-encodes the image without metadata. Built-in interior art remains available when an upload is rejected or disabled.","Image data is stored in this world on the server, not inside the backpack item.");
        page(out,"Private interiors","Privacy and moderation","midnight_cat",
            "Images are owner-only by default, even before moderation approval. Sharing requires the server sharing switch, approval, and an explicit viewer UUID. Merely holding a bag does not grant image access.\n\nOperators can inspect, reject and delete uploads in the moderation library. Owners can revoke viewers or delete images. The client keeps downloaded images in memory only and releases them on close/disconnect.\n\nAnyone permitted to view an image can still capture it. Revocation cannot erase screenshots or copies already saved elsewhere.","Current quotas: "+o.imagesPerPlayer+" images/player, "+o.imagesTotal+" images/world, "+o.imageWorldMiB+" MiB/world. One upload/player/minute.");
        page(out,"Field notes","Backpacks on monsters","spooky",
            "The optional monster-backpack feature is "+(o.monsterBackpacks?"ON":"OFF")+". Eligible natural or chunk-generation spawns have a "+pct(o.monsterChance)+" selection chance, then a "+pct(o.monsterDropChance)+" player-kill drop chance.\n\nThe decorative bag marker does not replace armor or copy a player inventory. A successful reward creates a fresh, empty starter backpack with its own identity.\n\nNamed mobs, pre-existing custom data, equipment and ineligible spawn types are skipped. Full-pack Apotheosis interaction still needs acceptance testing.","No protected inventory contents are placed in a monster reward.");
        page(out,"Field notes","A globe for one keepsake","item:anyastorage:keepsake_shelf",
            "Craft a Keepsake Globe with three glass blocks and any plank. Put glass at the top center, middle left and middle right. Put the plank at the bottom center. Place the globe anywhere you can normally build.\n\nRight-click with an item to display one. Right-click with an empty hand to pick it up again. Anyone with claim access can use a public globe. Breaking it drops the keepsake too.",
            "Inside a pocket, use the room editor to place a globe. The room's normal permissions apply. An old private shelf keeps its item sealed until its original owner takes it out.");
        page(out,"Field notes","Parcels for a friend","item:anyastorage:delivery_hatch",
            "Place a Delivery Hatch and look at it while using /anya furniture invite followed by an online player's name. Invited senders can use the hatch with a held stack or use /anya furniture deliver followed by a short note.\n\nAs owner, sneak and use an empty hand to collect a whole parcel and read its note. Make room for the whole stack. Use /anya furniture close to remove all invitations.",
            "Each hatch holds nine parcels. Pocket senders also need Visit and Deposit. Other senders cannot open the mailbox or collect.");
        page(out,"Pocket laboratory","Try a room layout","item:anyastorage:keepsake_shelf",
            "With room editing enabled, visit your pocket with Build access. Hold a plain supported block or furnishing, aim at a surface, and use /anya pocket build plan add. You can walk through the outlines while trying out the layout.\n\nUse plan undo to remove the last mark or plan cancel to clear the draft. Stand within reach and sight of all the marked faces, then use /anya pocket build plan apply. The blocks come from your inventory.",
            "Plan up to eight nearby spots. Furnishings and storage chests also need View, Deposit and Withdraw. The exit and original chests stay protected.");
        recipe(out,level,"Pocket laboratory","Skygrid Cube","item:anyastorage:home_void_cube",
            "Craft the entrance with two Diamonds and one Ancient Debris. Put one in each of three slots in your inventory crafting grid or a crafting table.\n\nPocket access is "+(dev.anya.storage.pocket.PocketService.enabled()?"enabled":"off")+". An operator can run /anya pocket enable in a test world. You can craft the entrance before enabling travel. Room editing has its own setting.\n\nFor 216 slots of ordinary placed storage see UwU Storage Cube under Home storage.","home_void_cube");
        page(out,"Pocket laboratory","Link, choose, visit","item:anyastorage:home_void_cube",
            "Place the entrance near your ordinary home hub. Sneak-use your tablet on the hub to bind it, then sneak-use that item on the entrance. The same player must own both. The item now opens this pocket's storage from within the hub radius.\n\nBefore the first visit, look at the entrance and use /anya pocket theme galaxy, beach or wasteland. These are modest block palettes, not realistic scenery or live portals. The choice becomes fixed when preparation begins.\n\nRight-click the entrance to enter. The prototype reserves a 32x32 plot with four real protected chests. Editing is separately gated by pocketEditing; /anya pocket build explains the narrow supported subset. Automation stays locked.","The 32x32 plot covers four chunks. A boundary ring, active-player view distance and ticket propagation require additional work and chunks.");
        page(out,"Pocket laboratory","Separate Visit and storage rights","item:minecraft:iron_door",
            "Use the ordinary hub's Member Permissions. Visit permits entry only; View, Deposit and Withdraw remain separate. A guest with Visit alone cannot see or take chest contents. Physical pocket chest clicks use those same server-checked permissions.\n\nA regular linked tablet works within the loaded home hub's spherical radius. Feeding and upgrading that hub changes the radius. A bound UwU Tablet can reach its destination from any distance or dimension, including in Survival. Claim checks can deny access; a claim never expands the radius to its outline.\n\nActive visits or storage menus hold bounded temporary leases. Closing the last menu/visit releases Anya's requests. Physical visits last until you leave or lose access. Remote storage sessions have a time limit.","Four original chests stay fixed. The gated builder can add up to four optional 54-slot Anya chests on clear room floor. Other containers, machines and live rendering remain unsupported.");
        page(out,"Pocket laboratory","Build without granting storage","item:minecraft:cherry_planks",
            "Room editing is "+(o.pocketEditing?"enabled for this test server":"OFF")+". Visit your pocket and right-click the air with the tablet linked to that room. Owners can build as soon as they enter. Guests with Build permission can click Edit Mode to start. Open the tablet again and click the wrench marked ON to finish.\n\nWith editing on, right-click a nearby face with a plain supported block in your main hand to place it. Left-click a placed block to return it to your inventory. Try planks, glass, or a cherry log. Click a side face to lay a log sideways. On the room tablet, owners can use Remove guests to send visitors home while preserving their permissions.\n\nThe owner can give a guest both Visit and Build through the hub's Member Permissions. View, Deposit and Withdraw separately control access to stored items.","The shell, original chests, chest approaches and landing/exit path stay protected.");
        page(out,"Pocket laboratory","Place a room chest","item:anyastorage:chest",
            "Craft a Labelled Storage Chest with a chest in the middle, iron ingots in the corners and planks in the other spaces.\n\nOwners can build on arrival when room editing is enabled. Guests with Build access can turn on Edit Mode in their linked tablet. Hold a plain Labelled Storage Chest and right-click a clear floor spot away from the entrance, exit and starter chests. Each chest adds 54 slots. You need Visit, Build, View, Deposit and Withdraw. Left-click an extra chest to collect it ONLY when empty. The original four chests cannot be removed.","The entrance updates its closed-chest picture for a permitted viewer when the room is loaded.");
        page(out,"Pocket laboratory","Return and recover","item:minecraft:lodestone",
            "Right-click the pocket lodestone or use /anya pocket exit. A durable return record is written before entry. Reconnecting, including after restart, returns an unfinished visitor home instead of granting a new visit.\n\nRemoving or replacing the home anchor stops access; it does not copy, delete or regenerate pocket contents. /anya pocket lists your identities. The recorded owner can look at a replacement entrance, hold a hub-bound tablet, and use /anya pocket reanchor <identity> after all users leave.\n\nA blocked return uses a safe spawn fallback. A completely unsafe spawn requires administrator assistance; the return record is retained. Interrupted preparation fails closed instead of overwriting an uncertain room.","Pocket inventory uses the existing snapshot/conservation transactions. Hard crashes across Minecraft's separate player and chunk saves are not an atomic database transaction.");
        page(out,"Field notes","What this milestone does not do","futuristic",
            "Live portal rendering and unrestricted pocket building are NOT implemented. The room editor is separately gated. Looking at the cube leaves the destination unloaded if nobody is using it. The displayed diagram covers registered chest positions only.\n\nThe pocket prototype is gated for disposable testing. Two real connected clients, OPAC cross-dimension behavior, Carry On, graves, forced disconnects and the target renderer stack still need full-pack acceptance.\n\nDo not remove existing storage mods, replace installed profiles, or open valuable worlds just because this guide describes a prototype.","Implementation, isolated tests and actual full-pack acceptance are different checkpoints.");
        recipe(out,level,"Field notes","Recover this book","item:anyastorage:ancient_technology",
            "Automatic first-login grant: "+(o.tutorialGrant?"ON":"OFF")+". It is recorded per player, not tied to keeping the book. Death, respawn, reconnect and ordinary restart do not grant another copy. A full inventory defers the grant instead of dropping it.\n\nLost your copy? /anya guide recover consumes one plain Book. The recovery recipe below is another option when enabled.\n\nThe grant marker is committed before delivery. A crash at that exact boundary may miss the item rather than duplicate it; explicit recovery handles that case.","ancient_technology");
        page(out,"Field notes","Pack for a trip","plain",
            "Arrange the supplies you want in your hotbar. Open a bag, chest or terminal and choose Field kit. Type a name if you like, then hold Shift and right-click one of the three trip kits to save it. Click that kit before your next outing to collect what is missing from the storage you opened. Named and enchanted variants match exactly.\n\nOpen Keep counts, enter a number, then click an inventory item to leave that many of its type on you during QuickStack and Dump. Items in protected slots count toward the total. Use zero to clear a rule. Amber borders show stacks left behind when a transfer could only move part of your inventory.",
            "Search for an item and Shift-right-click the result to mark its physical chest during an authorized visit.");
        page(out,"Field notes","Undo a dump","plain",
            "Shift-click Dump, or choose Undo last dump in Field kit, within one minute. Undo checks your current View and Withdraw permissions and returns the deposited items it can still identify in the same storage. Anya chests, cubes and backpacks support Undo. Vanilla and other external containers can receive items normally. The dump report shows how many items went to each. A full inventory or a changed chest can reduce the amount returned.\n\nFor a nearby dump made with a key or /anya dump, use /anya dump undo while in the same area. You can also assign Undo last nearby dump in Controls.",
            "Keep counts and trip kits belong to your player. Undo expires when you leave the server.");
        page(out,"Field notes","Reading the entrance window","futuristic",
            "Look directly at any face of a nearby Skygrid Cube, including the top or bottom. The public illustration shows the starter room. With View permission and an already loaded room, the cover draws permitted room blocks and registered chests. Stored items and private images stay private.\n\nTurn away to see the abstract veil again. Looking at the cube does not open a chest, load the room or move you. Visiting uses a separate permission and action.\n\nThe experimental pocket gates and limited room editor still apply. Full-pack testing of protection, inventory movers, recovery and the target renderer is still pending.","The entrance can show its public illustration even when pockets are off.");
        if(out.size()>128)throw new IllegalStateException("Guide page budget exceeded");return List.copyOf(out);
    }
    private static GuidePage.Cell cell(String id){
        ResourceLocation key=ResourceLocation.tryParse(id);Item item=key==null?Items.AIR:BuiltInRegistries.ITEM.get(key);
        return new GuidePage.Cell(clean(id,128),clean(item==Items.AIR?id:new ItemStack(item).getHoverName().getString(),128));
    }
    private static String cubeUnavailable(ServerLevel level, Config.Data config) {
        boolean endgame = Boolean.TRUE.equals(config.recipes.get("endgame"));
        if (config.cubeRecipe == 0) return endgame ? "The Cube recipe is set to OFF." : "The Cube recipe and Endgame recipe group are both off.";
        if (!endgame) return "The Endgame recipe group is off.";
        if (level.getRecipeManager().byKey(ExpansionPackets.id("uwu_cube")).filter(holder -> holder.value() instanceof CubeRecipe).isEmpty())
            return "The built-in cube recipe is missing from the loaded recipes.";
        return "";
    }
    private static void cubePage(List<GuidePage> out, int mode, String cost, String unavailable) {
        String body = "This placed storage block holds 216 ordinary slots. Craft it as a separate container and move your items into it when ready."
            + "\n\nUse " + cost + ". Fill all nine crafting cells in any arrangement. The Storage Core page shows its own shaped recipe."
            + "\n\nOperators can open /anya storage and choose Recipe gates / endgame choice to set the Cube recipe mode. The Endgame recipe group must also be enabled."
            + "\n\nThis cube joins a nearby hub's normal storage scan. See Skygrid Cube in Pocket laboratory for the experimental room entrance.";
        if (mode == 2) body += "\n\nLand the final blow on a tracked End dragon in Survival to earn a Dragon Victory Token.";
        String note = "RECIPE UNAVAILABLE. " + unavailable;
        String heading = "";
        List<GuidePage.Cell> cells = List.of();
        if (unavailable.isEmpty()) {
            GuidePage.Cell star = cell("minecraft:nether_star");
            GuidePage.Cell dragon = cell(mode == 2 ? "anyastorage:dragon_victory_token" : "minecraft:dragon_breath");
            cells = List.of(dragon, star, dragon, star, cell("anyastorage:storage_core"), star, dragon, star, dragon);
            heading = "Shapeless recipe: 1 UwU Storage Cube";
            note = "Active recipe. Put one ingredient in each of the nine cells.";
        }
        out.add(new GuidePage("Home storage", "UwU Storage Cube", "item:anyastorage:uwu_cube", body, note, heading, cells));
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
