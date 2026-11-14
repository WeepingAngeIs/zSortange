"""One-shot reviewed source corrections. Applied and committed before CI compilation, then removed."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
pending={}
def edit(path,old,new):
    p=root/path
    text=pending.get(p,p.read_text())
    if text.count(old)!=1:raise RuntimeError(f'{path}: expected exactly one match for {old[:90]!r}, found {text.count(old)}')
    pending[p]=text.replace(old,new,1)
def java(name,old,new):edit(f'src/main/java/dev/anya/storage/{name}.java',old,new)
edit('build.gradle','classpath = sourceSets.test.runtimeClasspath','classpath = sourceSets.test.runtimeClasspath + sourceSets.main.compileClasspath')
edit('build.gradle',"tasks.named('check') { dependsOn tasks.named('coreTest') }","tasks.named('check') { dependsOn tasks.named('coreTest') }\ntasks.named('sourcesJar') { dependsOn 'generateAssets' }")
java('AnyaStorage','Network.clear(); Safety.clear();','Network.clear(); Safety.clear(); Transactions.clear();')
java('Config','public Map<String,Integer> oreValues=new LinkedHashMap<>();','public Map<String,Integer> oreValues=new LinkedHashMap<>();\n        public Map<String,String> categoryRules=new LinkedHashMap<>();')
java('Config','require(d.oreValues!=null && d.oreValues.size()<=128,"At most 128 ore rules");','''require(d.categoryRules!=null && d.categoryRules.size()<=256,"At most 256 category rules");
        d.categoryRules.forEach((key,category)->require(key!=null && validId(key.startsWith("#")?key.substring(1):key) && CATEGORIES.contains(category) && !category.equals("any"),"Invalid category rule "+key));
        require(d.oreValues!=null && d.oreValues.size()<=128,"At most 128 ore rules");''')
java('Categories','String path=BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();','''String direct=Config.get().categoryRules.get(id(s));if(direct!=null)return direct;
        for(var rule:Config.get().categoryRules.entrySet())if(rule.getKey().startsWith("#")&&s.getTags().anyMatch(tag->tag.location().toString().equals(rule.getKey().substring(1))))return rule.getValue();
        String path=BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();''')
java('RecipeCondition','case "backpacks"->d.backpacks;','case "backpacks"->d.backpacks&&d.tier("starter").enabled;')
java('Network','if(result.size()>=Config.get().maxContainers)break;if(!p.serverLevel().hasChunkAt(pos))continue;','if(result.size()>=Config.get().maxContainers)break;if(!p.serverLevel().hasChunkAt(pos)||!within(center,pos,hub==null?Config.get().quickRadius:hub.radius()))continue;')
java('BagInventory','''int size=Math.max(9,Math.min(216,data.getInt("Capacity")));
        items=NonNullList.withSize(size,ItemStack.EMPTY);
        observed=stack.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY);observed.copyInto(items);''','''int size=data.getInt("Capacity");
        observed=stack.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY);
        long storedSize=observed.stream().count();
        if(size<9||size>216||storedSize>216)throw new IllegalArgumentException("Invalid backpack capacity; contents left untouched for recovery");
        size=Math.max(size,(int)storedSize);items=NonNullList.withSize(size,ItemStack.EMPTY);observed.copyInto(items);''')
java('BagInventory','&&item.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY).equals(ItemContainerContents.EMPTY);','&&!item.has(DataComponents.BUNDLE_CONTENTS)&&!item.has(DataComponents.BLOCK_ENTITY_DATA)&&item.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY).equals(ItemContainerContents.EMPTY);')
java('StorageEntity','public long profileRevision;','public long profileRevision;\n    public boolean showLabels=true;')
java('StorageEntity','''public static void tick(Level level,BlockPos pos,BlockState state,StorageEntity be){be.previousOpenness=be.openness;be.openness=Math.max(0,Math.min(1,be.openness+(be.viewers>0?0.12f:-0.12f)));}''','''public static void tick(Level level,BlockPos pos,BlockState state,StorageEntity be){
        be.previousOpenness=be.openness;be.openness=Math.max(0,Math.min(1,be.openness+(be.viewers>0?0.12f:-0.12f)));
        if(!level.isClientSide&&be.showLabels!=Config.get().labels){be.showLabels=Config.get().labels;level.sendBlockUpdated(pos,state,state,3);}
    }''')
java('StorageEntity','tag.putString("Symbol",symbol);return tag;','tag.putString("Symbol",symbol);tag.putBoolean("ShowLabels",level!=null&&!level.isClientSide?Config.get().labels:showLabels);return tag;')
java('StorageEntity','{symbol=tag.getString("Symbol");}','{symbol=tag.getString("Symbol");showLabels=!tag.contains("ShowLabels")||tag.getBoolean("ShowLabels");}')
java('ChestRenderer','String symbol=Config.SYMBOLS.contains(entity.symbol)?entity.symbol:"heart";','if(!entity.showLabels){pose.popPose();return;}\n        String symbol=Config.SYMBOLS.contains(entity.symbol)?entity.symbol:"heart";')
java('Upgrades','return Boolean.TRUE.equals(Config.get().upgrades.get(id))&&installed(bag,id);','return Config.get().backpacks&&Boolean.TRUE.equals(Config.get().upgrades.get(id))&&installed(bag,id);')
java('Upgrades','if(!Boolean.TRUE.equals(Config.get().upgrades.get(id)))','if(!Config.get().backpacks||!Boolean.TRUE.equals(Config.get().upgrades.get(id)))')
java('Upgrades','''int fuel=data.getInt("FuelTicks"),progress=data.getInt("SmeltTicks");''','''int fuel=data.getInt("FuelTicks"),progress=data.getInt("SmeltTicks");
        String recipeId=recipe.id().toString();ItemStack currentInput=bag.getItem(inputSlot).copyWithCount(1);
        ItemStack previousInput=ItemStack.parseOptional(bag.player.registryAccess(),data.getCompound("SmeltInput"));
        if(!recipeId.equals(data.getString("SmeltRecipe"))||!ItemStack.isSameItemSameComponents(currentInput,previousInput))progress=0;
        data.putString("SmeltRecipe",recipeId);data.put("SmeltInput",currentInput.save(bag.player.registryAccess()));''')
java('Upgrades','''if(output.isEmpty())return;plan.work(bag)[inputSlot].shrink(1);ItemStack remaining=output.copy();plan.insert(bag,remaining);
            if(remaining.isEmpty()){
                plan.authorizeRecipe(List.of(consumed),List.of(output));String error=plan.commit(bag.player.getServer());''','''if(output.isEmpty())return;plan.work(bag)[inputSlot].shrink(1);ItemStack remaining=output.copy();plan.insert(bag,remaining);
            boolean fits=remaining.isEmpty();List<ItemStack> outputs=new ArrayList<>();outputs.add(output.copy());
            for(ItemStack remainder:recipe.value().getRemainingItems(new SingleRecipeInput(consumed)))if(!remainder.isEmpty()){
                outputs.add(remainder.copy());ItemStack leftover=remainder.copy();plan.insert(bag,leftover);fits&=leftover.isEmpty();
            }
            if(fits){
                plan.authorizeRecipe(List.of(consumed),outputs);String error=plan.commit(bag.player.getServer());''')
java('Client','imageWidth=194;imageHeight=258;titleLabelX=12;titleLabelY=8;inventoryLabelX=12;inventoryLabelY=157;','imageWidth=194;imageHeight=240;titleLabelX=12;titleLabelY=8;inventoryLabelX=12;inventoryLabelY=143;')
java('Client','leftPos+12,topPos+23,170,16','leftPos+12,topPos+18,170,14')
java('Client','playerInventoryTitle,12,157','playerInventoryTitle,12,143')
java('StoreMenu','DIAGNOSTICS, PRESETS, CONFIG_CONFIRM, SORT_CONFIRM }','DIAGNOSTICS, PRESETS, CONFIG_CONFIRM, SORT_CONFIRM, FEED_CONFIRM, CATEGORIES }')
java('StoreMenu','private boolean favoritesOnly;','private boolean favoritesOnly;\n    private int feedSlot,feedCount,feedPoints,feedBefore;\n    private long feedRevision;\n    private ItemStack feedSnapshot=ItemStack.EMPTY;')
java('StoreMenu','17+col*18,44+row*18','17+col*18,34+row*18')
java('StoreMenu','17+col*18,169+row*18','17+col*18,155+row*18')
java('StoreMenu','17+col*18,227','17+col*18,213')
java('StoreMenu','case ADMIN,LIMITS,TIERS,TIER,ORES,RECIPES,UPGRADE_GATES,PRESETS,CONFIG_CONFIRM->true;','case ADMIN,LIMITS,TIERS,TIER,ORES,RECIPES,UPGRADE_GATES,PRESETS,CONFIG_CONFIRM,CATEGORIES->true;')
java('StoreMenu','(page==Page.BASE||page==Page.ROUTE)','(page==Page.BASE||page==Page.ROUTE||page==Page.FEED_CONFIRM)')
java('StoreMenu','case SORT_CONFIRM->sortConfirm();','case SORT_CONFIRM->sortConfirm();case FEED_CONFIRM->feedConfirm();case CATEGORIES->categories();')
java('StoreMenu','copy.remove(DataComponents.CONTAINER);if(copy.is(Content.BACKPACK.get()))copy.remove(DataComponents.CUSTOM_DATA);','Component originalName=copy.getHoverName().copy();copy.remove(DataComponents.CONTAINER);copy.remove(DataComponents.BUNDLE_CONTENTS);if(copy.is(Content.BACKPACK.get())){copy.remove(DataComponents.CUSTOM_DATA);copy.set(DataComponents.CUSTOM_NAME,originalName);}')
java('StoreMenu','MainInventory main=main();List<Network.Target> targets=targets();List<Container> all=new ArrayList<>();all.add(main);all.add(entry.inventory);','''MainInventory main=main();List<Network.Target> targets=targets();
        if(hub!=null&&targets.stream().noneMatch(t->t.inventory()==entry.inventory)){AnyaStorage.tell(owner,"That source is no longer accessible. Permissions and range have been rechecked.");return;}
        List<Container> all=new ArrayList<>();all.add(main);all.add(entry.inventory);''')
java('StoreMenu','''if(slot<0||slot>=36)return;
        if(page==Page.PREFS)''','''if(slot<0||slot>=36)return;
        if(page==Page.BASE){selectOre(slot,click);return;}
        if(page==Page.PREFS)''')
java('StoreMenu','button(34,Items.IRON_DOOR,"My protected slots",c->navigate(Page.PREFS));','button(34,Items.IRON_DOOR,"My protected slots",c->navigate(Page.PREFS));\n        button(37,Items.BOOK,"Category rules: items and tags",c->navigate(Page.CATEGORIES));')
java('StoreMenu','footer(Page.ADMIN);button(51,Items.HOPPER,"Add held material tier"','footer(Page.ADMIN);button(46,Items.ARROW,"Previous tiers",c->offset=Math.max(0,offset-36));button(47,Items.ARROW,"Next tiers",c->offset+=36);button(51,Items.HOPPER,"Add held material tier"')
java('StoreMenu','ServerPlayer target=owner.getServer().getPlayerList().getPlayerByName(input);if(target==null)throw new IllegalArgumentException("Enter the exact name of an online player.");if(hub.trusted.size()>=64&&!hub.trusted.contains(target.getUUID()))throw new IllegalArgumentException("Trust limit reached.");if(!hub.trusted.remove(target.getUUID()))hub.trusted.add(target.getUUID());','ServerPlayer target=owner.getServer().getPlayerList().getPlayerByName(input);UUID targetId;try{targetId=target!=null?target.getUUID():UUID.fromString(input);}catch(IllegalArgumentException e){throw new IllegalArgumentException("Enter an online name or a full player UUID.");}if(hub.trusted.size()>=64&&!hub.trusted.contains(targetId))throw new IllegalArgumentException("Trust limit reached.");if(!hub.trusted.remove(targetId))hub.trusted.add(targetId);')
java('StoreMenu','"Enter an online name, then click to add/remove."','"Enter an online name or full UUID, then click to add/remove."')
java('StoreMenu','"Trust an online player"','"Manage trusted players"')
java('StoreMenu','c->draft.oreValues.put(id,number(c,draft.oreValues.get(id),1))','c->{if(c.button==2)draft.oreValues.remove(id);else draft.oreValues.put(id,number(c,draft.oreValues.get(id),1));}')
java('StoreMenu','"Accepted values are 1..100000."','"Accepted values are 1..100000. Middle-click removes the rule."')
java('StoreMenu','    private void diagnostics(){','''    private void selectOre(int slot,Click click){
        if(hub==null||!hub.ownedBy(owner))return;
        ItemStack item=owner.getInventory().getItem(slot);
        if(!Transactions.same(item,playerSeen[slot]))throw new IllegalArgumentException("Inventory changed; select the refreshed slot.");
        Integer value=Config.get().oreValues.get(Categories.id(item));
        if(item.isEmpty()||value==null)throw new IllegalArgumentException("This item has no configured ore-feeder value.");
        if(item.is(Content.BACKPACK.get())||item.has(DataComponents.BLOCK_ENTITY_DATA)||item.has(DataComponents.BUNDLE_CONTENTS)||!item.getOrDefault(DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.EMPTY).equals(net.minecraft.world.item.component.ItemContainerContents.EMPTY))throw new IllegalArgumentException("Containers cannot be burned as ore fuel.");
        feedCount=Math.min(click.button==1?1:item.getCount(),Math.max(0,(1000000-hub.oreProgress)/value));
        if(feedCount==0)throw new IllegalArgumentException("Ore progress is full; upgrade before feeding more.");
        feedSlot=slot;feedPoints=Math.multiplyExact(feedCount,value);feedBefore=hub.oreProgress;feedRevision=Config.get().revision;feedSnapshot=item.copy();navigate(Page.FEED_CONFIRM);
    }
    private void feedConfirm(){
        button(13,feedSnapshot.getItem(),"Consume "+feedCount+" "+feedSnapshot.getHoverName().getString(),null,"Adds "+feedPoints+" upgrade points.","Stored progress: "+feedBefore+" -> "+(feedBefore+feedPoints),"Current radius: "+hub.radius()+"; next radius: "+hub.nextRadius(),"Next upgrade costs "+hub.upgradeCost()+" points.","Feeding does not automatically buy the radius upgrade.");
        button(45,Items.ARROW,"Cancel feeding",c->navigate(Page.BASE));
        button(53,Items.EMERALD,"Confirm feeding",c->{
            if(Config.get().revision!=feedRevision||hub.oreProgress!=feedBefore||!Transactions.same(owner.getInventory().getItem(feedSlot),feedSnapshot))throw new IllegalArgumentException("Ore, progress or settings changed. Select the resource again.");
            MainInventory inventory=new MainInventory(owner);Transactions.Plan plan=new Transactions.Plan(List.of(inventory),()->stillValid(owner)&&hub.ownedBy(owner)&&Config.get().revision==feedRevision&&hub.oreProgress==feedBefore);
            ItemStack consumed=plan.work(inventory)[feedSlot].copyWithCount(feedCount);plan.work(inventory)[feedSlot].shrink(feedCount);plan.authorizeRecipe(List.of(consumed),List.of());
            String error=plan.commit(owner.getServer());if(!error.isEmpty())throw new IllegalArgumentException(error);
            hub.oreProgress=feedBefore+feedPoints;hub.profileChanged();AnyaStorage.tell(owner,"Added "+feedPoints+" ore points. Choose the radius upgrade button separately to spend them.");navigate(Page.BASE);
        });
    }
    private void categories(){
        List<String> keys=new ArrayList<>(draft.categoryRules.keySet());offset=Math.min(offset,Math.max(0,((keys.size()-1)/45)*45));
        List<String> categories=Config.CATEGORIES.stream().filter(s->!s.equals("any")).toList();
        for(int i=0;i<45&&offset+i<keys.size();i++){
            String key=keys.get(offset+i),category=draft.categoryRules.get(key);
            button(i,key.startsWith("#")?Items.BOOK:material(key),key+" -> "+category,c->{if(c.button==2)draft.categoryRules.remove(key);else draft.categoryRules.put(key,categories.get(Math.floorMod(categories.indexOf(category)+(c.button==1?-1:1),categories.size())));},"Left/right cycles categories; middle-click removes.","Exact item rules override tag rules.","Tag rules are evaluated in displayed insertion order.");
        }
        footer(Page.ADMIN);
        button(50,Items.HOPPER,"Add held item rule",c->{ItemStack held=owner.getMainHandItem();if(held.isEmpty())throw new IllegalArgumentException("Hold an item before opening settings.");draft.categoryRules.putIfAbsent(Categories.id(held),"misc");});
        button(51,Items.BOOK,"Add rule from input",c->{String key=input;if(!Config.validId(key.startsWith("#")?key.substring(1):key))throw new IllegalArgumentException("Enter namespace:item or #namespace:tag.");if(!key.startsWith("#")&&!Config.installed(key))throw new IllegalArgumentException("That item is not installed.");draft.categoryRules.putIfAbsent(key,"misc");},"For example minecraft:diamond or #c:ores.","Rules are only active after reviewing and applying the draft.");
    }
    private void diagnostics(){''')
# All replacements are validated before any tracked file is changed.
for path,text in pending.items():path.write_text(text)
Path(__file__).unlink()
print(f'Applied reviewed corrections to {len(pending)} files; source is ready for a normal commit and build.')
