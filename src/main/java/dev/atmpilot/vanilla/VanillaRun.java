package dev.atmpilot.vanilla;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import static dev.atmpilot.vanilla.VanillaWorld.*;

/** Opt-in, world-bound vanilla survival runner. No commands, flight, server internals or JEI. */
public final class VanillaRun {
    private VanillaRun() {}
    private static boolean selected,running,dragonDied,enteredEnd;
    private static String state="idle",stage="bootstrap",reason="Not started",world="",playerId="",runId="";
    private static VanillaTask task;private static long ticks,stageTicks;private static int failures,exploreIndex,deaths,deathWait,quiet,foodWait;
    private static BlockPos camp,table,furnace,portal,deathPoint,stronghold;
    private static String deathDimension="",taskDimension="";private static Vec3 eyeOrigin;
    private static final List<VanillaMath.Ray> rays=new ArrayList<>();
    private static final Map<String,BlockPos> portals=new HashMap<>();
    private static final Map<String,BlockPos> searchOrigins=new HashMap<>();
    private static final Set<BlockPos> denied=new HashSet<>();
    private static Vec3 previousPosition;private static String previousDimension="";
    private static VanillaTask reflex;private static long reflexCooldown;
    private static int maxTicks=1_728_000;
    public static boolean selected(){return selected;}
    public static boolean running(){return running;}
    public static String worldKey(){
        var m=Minecraft.getInstance();if(m.player==null||m.level==null)return "";
        if(m.getSingleplayerServer()!=null)return "local:"+m.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        return m.getCurrentServer()==null?"":"server:"+m.getCurrentServer().ip;
    }
    private static Path file(){
        try{var hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((world+"/"+playerId).getBytes(StandardCharsets.UTF_8))).substring(0,20);
            return PilotMod.CONTROLLER.directory.resolve("vanilla").resolve(hash+".json");
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    public static JsonObject status(){
        var r=Jsons.obj("mode","vanilla_survival","selected",selected,"running",running,"state",state,"stage",stage,"reason",reason,
            "worldKey",worldKey(),"boundWorld",world,"runId",runId,"ticks",ticks,"deaths",deaths,"dragonDeathObserved",dragonDied,
            "active",task==null?null:task.describe(),"flight",false,"teleport",false,"commands",false,"jeiRequired",false,
            "testedEndToEnd",false,"version","experimental-1");
        if(camp!=null)r.add("camp",pos(camp));if(stronghold!=null)r.add("strongholdEstimate",pos(stronghold));
        r.add("eyeRays",Jsons.GSON.toJsonTree(rays));return r;
    }
    private static JsonObject pos(BlockPos p){return Jsons.obj("x",p.getX(),"y",p.getY(),"z",p.getZ());}
    private static BlockPos readPos(JsonObject root,String key){return root.has(key)?Actions.pos(root.getAsJsonObject(key)):null;}
    private static void save(){
        if(world.isEmpty())return;
        try{var r=status();r.addProperty("schema",1);r.addProperty("player",playerId);r.addProperty("savedAt",Instant.now().toString());
            if(table!=null)r.add("table",pos(table));if(furnace!=null)r.add("furnace",pos(furnace));if(portal!=null)r.add("portal",pos(portal));
            if(deathPoint!=null)r.add("deathPoint",pos(deathPoint));r.addProperty("deathDimension",deathDimension);r.addProperty("enteredEnd",enteredEnd);
            r.addProperty("exploreIndex",exploreIndex);JsonObject ps=new JsonObject();portals.forEach((k,v)->ps.add(k,pos(v)));r.add("portals",ps);
            var p=file();Files.createDirectories(p.getParent());var tmp=p.resolveSibling(p.getFileName()+".tmp");Files.writeString(tmp,Jsons.GSON.toJson(r),StandardCharsets.UTF_8);
            Files.move(tmp,p,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }catch(Exception e){running=false;state="needs_attention";reason="Checkpoint could not be saved: "+e.getMessage();Actions.releaseControls();}
    }
    public static JsonObject start(JsonObject args){
        var m=mc();boolean resume=Jsons.bool(args,"resume",false);
        if(PilotMod.CONTROLLER.running()||running)throw new IllegalStateException("Stop the current job before starting vanilla mode");
        boolean vanilla=BuiltInRegistries.BLOCK.keySet().stream().allMatch(k->k.getNamespace().equals("minecraft"))
            &&BuiltInRegistries.ITEM.keySet().stream().allMatch(k->k.getNamespace().equals("minecraft"));
        boolean fresh=true;for(int i=0;i<m.player.getInventory().getContainerSize();i++)if(!m.player.getInventory().getItem(i).isEmpty())fresh=false;
        VanillaPolicy.validateStart(worldKey(),Jsons.str(args,"confirmWorld",""),Jsons.bool(args,"allowWorldChanges",false),
            m.gameMode.getPlayerMode()==GameType.SURVIVAL&&!m.player.getAbilities().flying&&!m.player.getAbilities().mayfly&&m.level.getDifficulty()!=Difficulty.PEACEFUL,vanilla,fresh,resume);
        if(!m.player.containerMenu.getCarried().isEmpty())throw new IllegalStateException("Return cursor before starting");
        world=worldKey();playerId=m.player.getUUID().toString();runId=UUID.randomUUID().toString();camp=m.player.blockPosition();
        stage="bootstrap";ticks=stageTicks=0;deaths=failures=exploreIndex=deathWait=quiet=0;dragonDied=enteredEnd=false;
        task=null;reflex=null;table=furnace=portal=deathPoint=stronghold=null;rays.clear();portals.clear();searchOrigins.clear();denied.clear();
        previousPosition=m.player.position();previousDimension=m.level.dimension().location().toString();
        if(resume){
            try{var r=JsonParser.parseString(Files.readString(file())).getAsJsonObject();
                if(!world.equals(Jsons.str(r,"boundWorld",""))||!playerId.equals(Jsons.str(r,"player","")))throw new IllegalArgumentException("Checkpoint world/player mismatch");
                stage=Jsons.str(r,"stage","bootstrap");if(!VanillaPolicy.STAGES.contains(stage))throw new IllegalArgumentException("Unknown checkpoint stage");
                if(Set.of("exit","complete").contains(stage)&&!Jsons.bool(r,"dragonDeathObserved",false))throw new IllegalArgumentException("Completion checkpoint lacks observed dragon death");
                runId=Jsons.str(r,"runId",runId);camp=readPos(r,"camp");table=readPos(r,"table");furnace=readPos(r,"furnace");portal=readPos(r,"portal");stronghold=readPos(r,"strongholdEstimate");deathPoint=readPos(r,"deathPoint");
                deathDimension=Jsons.str(r,"deathDimension","");dragonDied=Jsons.bool(r,"dragonDeathObserved",false);enteredEnd=Jsons.bool(r,"enteredEnd",false);
                exploreIndex=Jsons.integer(r,"exploreIndex",0);deaths=Jsons.integer(r,"deaths",0);
                if(r.has("eyeRays"))for(var value:r.getAsJsonArray("eyeRays"))rays.add(Jsons.GSON.fromJson(value,VanillaMath.Ray.class));
                if(r.has("portals"))for(var e:r.getAsJsonObject("portals").entrySet())portals.put(e.getKey(),Actions.pos(e.getValue().getAsJsonObject()));
                // Never resume an in-flight inventory click, shot, placement or recipe request.
            }catch(Exception e){throw new IllegalStateException("Cannot resume this world's checkpoint",e);}
        }
        maxTicks=Math.max(1200,Math.min(3_456_000,Jsons.integer(args,"maxTicks",1_728_000)));
        m.options.pauseOnLostFocus=false;m.options.save();selected=running=true;state="running";reason="Reobserving world and inventory";save();return status();
    }
    public static void stop(String why){
        if(!selected)return;if(task!=null)try{task.cancel();}catch(Exception ignored){}if(reflex!=null)try{reflex.cancel();}catch(Exception ignored){}task=null;reflex=null;running=false;state="paused";reason=why;Actions.releaseControls();save();
    }
    public static JsonObject leave(){stop("Vanilla mode left by user");selected=false;return status();}
    private static void next(String name){stage=name;stageTicks=0;failures=0;exploreIndex=0;searchOrigins.clear();task=null;reason="Stage "+name;save();}
    private static void schedule(VanillaTask t){task=t;taskDimension=mc().level.dimension().location().toString();}
    public static void tick(){
        if(!running)return;var m=Minecraft.getInstance();
        if(m.player==null||m.level==null||m.gameMode==null){stop("Disconnected; explicit resume required");return;}
        if(!world.equals(worldKey())||!playerId.equals(m.player.getUUID().toString())){stop("World or player changed");return;}
        try{
            if(++ticks>maxTicks)throw new IllegalStateException("Run tick budget reached; checkpoint saved");stageTicks++;
            String currentDimension=m.level.dimension().location().toString();
            if(!previousDimension.equals(currentDimension)){
                if(previousPosition!=null)portals.put(previousDimension+"->"+currentDimension,BlockPos.containing(previousPosition));
                portals.put(currentDimension,mc().player.blockPosition());
                if(!currentDimension.equals("minecraft:the_end"))portals.put(currentDimension+"->"+previousDimension,mc().player.blockPosition());
                previousDimension=currentDimension;save();
            }
            previousPosition=m.player.position();
            if(m.player.getAbilities().flying||m.player.getAbilities().mayfly||m.gameMode.getPlayerMode()!=GameType.SURVIVAL)throw new IllegalStateException("Vanilla survival capability changed");
            if(m.screen instanceof WinScreen){if(dragonDied){state="complete";stage="complete";running=false;reason="Dragon death witnessed and exit credits entered";save();}return;}
            if(!m.player.isAlive()){
                if(deathPoint==null){deathPoint=m.player.blockPosition();deathDimension=m.level.dimension().location().toString();deaths++;save();}
                if(task!=null){task.cancel();task=null;}Actions.releaseControls();if(++deathWait==25)m.player.respawn();if(deathWait>400)throw new IllegalStateException("Respawn not acknowledged");return;
            }
            deathWait=0;
            if(m.level.dimension()==Level.END)for(var e:entities(180,x->x instanceof EnderDragon))if(((EnderDragon)e).getHealth()<=0){dragonDied=true;save();}
            if(!m.player.containerMenu.getCarried().isEmpty()&&task==null)throw new IllegalStateException("Unexpected cursor stack; recover before resuming");
            if(deathPoint!=null){recover();return;}
            if(task!=null&&!taskDimension.equals(m.level.dimension().location().toString())){task.cancel();task=null;reason="Dimension changed through native portal; replanning";save();}
            if(ticks%100==0)save();
            if(reflex!=null){if(reflex.tick()){reflex=null;reflexCooldown=ticks+10;}return;}
            if(ticks>=reflexCooldown&&survive())return;
            // Emergency behavior runs locally; never call /home or enable flight.
            if(task==null&&m.player.getFoodData().getFoodLevel()<16&&slot(VanillaWorld::isFood)>=0){schedule(new VanillaTasks.Eat());return;}
            if(task==null&&m.player.getFoodData().getFoodLevel()<14&&slot(VanillaWorld::isFood)<0&&m.level.dimension()!=Level.END){provideFood(24);return;}
            if(task==null&&m.level.getRawBrightness(m.player.blockPosition(),0)<5&&count("minecraft:torch")>0&&clear(m.player.blockPosition())&&floor(m.player.blockPosition().below())){schedule(new VanillaTasks.Place(m.player.blockPosition(),"minecraft:torch"));return;}
            if(task==null&&ticks%5==0&&m.screen==null){var threats=entities(5,e->e instanceof Monster&&!(e instanceof EnderMan)&&e.isAlive()&&!e.hasCustomName());
                if(!threats.isEmpty()&&m.player.getHealth()>5){schedule(new VanillaTasks.Fight(threats.getFirst(),false));return;}}
            if(task!=null){if(task.tick()){task=null;failures=0;Actions.releaseControls();}return;}
            if(stageTicks>240000)throw new IllegalStateException("Stage made no verified completion within its budget: "+stage);
            plan();
        }catch(Exception|LinkageError e){
            if(task!=null)try{task.cancel();}catch(Exception ignored){}task=null;Actions.releaseControls();reason=e.getClass().getSimpleName()+": "+e.getMessage();
            if(++failures>=6||reason.contains("cursor")||reason.contains("capability")){running=false;state="needs_attention";save();}
            else {reason+="; replanning from observed state ("+failures+"/6)";quiet=20;}
        }
    }
    private static boolean survive(){
        var p=mc().player;if(!p.containerMenu.getCarried().isEmpty())return false;
        boolean hungry=p.getFoodData().getFoodLevel()<14&&slot(VanillaWorld::isFood)>=0;
        var clouds=entities(5,e->e instanceof AreaEffectCloud);
        var creepers=entities(5,e->e instanceof Creeper c&&c.getSwellDir()>0);
        if(hungry||!clouds.isEmpty()||!creepers.isEmpty()||p.getHealth()<7){
            if(task!=null){task.cancel();task=null;}
            if(hungry){reflex=new VanillaTasks.Eat();return true;}
            Entity threat=!creepers.isEmpty()?creepers.getFirst():!clouds.isEmpty()?clouds.getFirst():entities(10,e->e instanceof Monster).stream().findFirst().orElse(null);
            if(threat!=null){var away=p.position().subtract(threat.position());double len=Math.max(.1,away.horizontalDistance());
                reflex=new VanillaNavigation(p.blockPosition().offset((int)Math.round(away.x/len*7),0,(int)Math.round(away.z/len*7)),1.5);return true;}
        }
        if((p.isOnFire()||p.fallDistance>5&&p.getDeltaMovement().y<-.6)&&mc().level.dimension()!=Level.NETHER&&count("minecraft:water_bucket")>0){
            for(int dy=1;dy<=5;dy++){var support=p.blockPosition().below(dy);if(floor(support)&&p.getEyePosition().distanceTo(Vec3.atCenterOf(support))<p.blockInteractionRange()){
                if(task!=null){task.cancel();task=null;}reflex=new VanillaTasks.Use("minecraft:water_bucket",support);return true;}}
        }
        return false;
    }
    private static boolean need(String item,int amount){if(count(item)>=amount)return false;schedule(ensure(item,amount,new HashSet<>()));return true;}
    private static boolean provideFood(int nutrition){
        int total=0;for(int i=0;i<36;i++){var s=mc().player.getInventory().getItem(i);var f=s.get(net.minecraft.core.component.DataComponents.FOOD);if(f!=null&&isFood(s))total+=f.nutrition()*s.getCount();}
        if(total>=nutrition)return false;
        for(String raw:List.of("beef","porkchop","chicken","mutton","rabbit","cod","salmon"))if(count("minecraft:"+raw)>0){String cooked="minecraft:cooked_"+raw;schedule(smelt("minecraft:"+raw,cooked,count(cooked)+Math.min(16,count("minecraft:"+raw)),new HashSet<>()));return true;}
        var animals=entities(48,e->e instanceof net.minecraft.world.entity.animal.Cow||e instanceof net.minecraft.world.entity.animal.Pig||e instanceof net.minecraft.world.entity.animal.Sheep||e instanceof net.minecraft.world.entity.animal.Chicken);
        animals=animals.stream().filter(e->!e.hasCustomName()&&mc().player.hasLineOfSight(e)).toList();
        if(!animals.isEmpty()){schedule(new VanillaTasks.Sequence(new VanillaTasks.Fight(animals.getFirst(),false),new VanillaTasks.Collect(null)));return true;}
        schedule(explore(mc().player.blockPosition().getY()));return true;
    }
    private static VanillaTask ensure(String item,int amount,Set<String> chain){
        if(!chain.add(item))throw new IllegalStateException("Recipe dependency cycle "+item);
        if(chain.size()>16)throw new IllegalStateException("Recipe depth limit");
        if(item.equals("minecraft:iron_ingot"))return smelt("minecraft:raw_iron",item,amount,chain);
        if(item.equals("minecraft:cooked_beef"))return smelt("minecraft:beef",item,amount,chain);
        var recipes=mc().level.getRecipeManager().getRecipes().stream().filter(r->r.value().getType()==RecipeType.CRAFTING&&id(r.value().getResultItem(mc().level.registryAccess())).equals(item)).sorted(Comparator.comparingInt(r->recipeCost(r.value()))).toList();
        for(var holder:recipes){var recipe=holder.value();var ingredients=new LinkedHashMap<String,Integer>();
            for(var ingredient:recipe.getIngredients()){
                if(ingredient.isEmpty())continue;String selected=choose(ingredient);ingredients.merge(selected,1,Integer::sum);
            }
            for(var e:ingredients.entrySet())if(count(e.getKey())<e.getValue())return ensure(e.getKey(),e.getValue(),new HashSet<>(chain));
            if(recipe.canCraftInDimensions(2,2)){VanillaWorld.close();return new VanillaTasks.Craft(holder,item,amount);}
            if(table==null||!loaded(table)||!mc().level.getBlockState(table).is(Blocks.CRAFTING_TABLE)){
                if(count("minecraft:crafting_table")==0)return ensure("minecraft:crafting_table",1,new HashSet<>());
                table=site();return new VanillaTasks.Place(table,"minecraft:crafting_table");
            }
            return new VanillaTasks.Sequence(new VanillaNavigation(table,3),new VanillaTasks.Open(table),new VanillaTasks.Craft(holder,item,amount));
        }
        return gather(item);
    }
    private static int recipeCost(Recipe<?> r){int cost=0;for(var i:r.getIngredients())if(!i.isEmpty())cost+=Arrays.stream(i.getItems()).anyMatch(s->count(id(s))>0)?0:10;return cost+r.getIngredients().size();}
    private static String choose(Ingredient i){
        for(var s:i.getItems())if(count(id(s))>0)return id(s);
        // Prefer the plank variant matching actual logs instead of assuming an oak spawn.
        for(var s:i.getItems())if(id(s).endsWith("_planks")){String log=id(s).replace("_planks","_log");if(count(log)>0)return id(s);}
        return Arrays.stream(i.getItems()).map(VanillaWorld::id).filter(s->s.startsWith("minecraft:")).sorted(Comparator.comparingInt(VanillaRun::rawCost)).findFirst().orElseThrow();
    }
    private static int rawCost(String id){if(id.equals("minecraft:oak_planks")||id.equals("minecraft:oak_log")||id.equals("minecraft:cobblestone")||id.equals("minecraft:coal")||id.equals("minecraft:iron_ingot"))return 0;return id.contains("deepslate")?20:5;}
    private static VanillaTask smelt(String input,String output,int target,Set<String> chain){
        if(count(input)<Math.min(64,target-count(output)))return ensure(input,Math.min(64,target-count(output)),chain);
        if(count(s->isLog(s)||id(s).endsWith("_planks")||id(s).equals("minecraft:coal")||id(s).equals("minecraft:charcoal"))<8)return gather("minecraft:oak_log");
        if(furnace==null||!loaded(furnace)||!mc().level.getBlockState(furnace).is(Blocks.FURNACE)){
            if(count("minecraft:furnace")==0)return ensure("minecraft:furnace",1,new HashSet<>());furnace=site();return new VanillaTasks.Place(furnace,"minecraft:furnace");}
        return new VanillaTasks.Sequence(new VanillaNavigation(furnace,3),new VanillaTasks.Open(furnace),new VanillaTasks.Smelt(input,output,target));
    }
    private static BlockPos site(){
        var c=mc().player.blockPosition();for(int r=1;r<=3;r++)for(int x=-r;x<=r;x++)for(int z=-r;z<=r;z++){
            var p=c.offset(x,0,z);if(clear(p)&&clear(p.above())&&floor(p.below())&&!p.equals(table)&&!p.equals(furnace))return p;}
        throw new IllegalStateException("No dry workstation site within reach");
    }
    private static VanillaTask gather(String item){
        Predicate<net.minecraft.world.level.block.state.BlockState> accept=s->false;
        if(item.endsWith("_log")||item.endsWith("_stem"))accept=s->s.is(BlockTags.LOGS);
        else if(item.equals("minecraft:cobblestone"))accept=s->s.is(Blocks.STONE)||s.is(Blocks.COBBLESTONE);
        else if(item.equals("minecraft:raw_iron"))accept=s->s.is(Blocks.IRON_ORE)||s.is(Blocks.DEEPSLATE_IRON_ORE);
        else if(item.equals("minecraft:diamond"))accept=s->s.is(Blocks.DIAMOND_ORE)||s.is(Blocks.DEEPSLATE_DIAMOND_ORE);
        else if(item.equals("minecraft:coal"))accept=s->s.is(Blocks.COAL_ORE)||s.is(Blocks.DEEPSLATE_COAL_ORE);
        else if(item.equals("minecraft:flint")||item.equals("minecraft:gravel"))accept=s->s.is(Blocks.GRAVEL);
        else if(item.equals("minecraft:obsidian"))accept=s->s.is(Blocks.OBSIDIAN);
        else if(item.equals("minecraft:beef")||item.equals("minecraft:feather")||item.endsWith("_wool")||item.equals("minecraft:string")||item.equals("minecraft:ender_pearl")||item.equals("minecraft:blaze_rod"))return hunt(item);
        else if(item.equals("minecraft:water_bucket"))return waterBucket();
        else throw new IllegalStateException("No vanilla acquisition strategy for "+item);
        var p=find(accept.and(s->true),20);
        if(p!=null&&!denied.contains(p)){
            if(!mineable(p)){if(count("minecraft:stone_pickaxe")==0)return ensure("minecraft:stone_pickaxe",1,new HashSet<>());if(count("minecraft:iron_pickaxe")==0)return ensure("minecraft:iron_pickaxe",1,new HashSet<>());return ensure("minecraft:diamond_pickaxe",1,new HashSet<>());}
            return new VanillaTasks.Sequence(new VanillaNavigation(p,3),new VanillaTasks.Mine(p),new VanillaTasks.Collect(p));
        }
        if(item.equals("minecraft:obsidian")){
            if(count("minecraft:water_bucket")==0)return waterBucket();
            var lava=find(s->s.getFluidState().isSource()&&s.is(Blocks.LAVA),20);
            if(lava!=null){for(var d:Direction.Plane.HORIZONTAL){var side=lava.relative(d);if(clear(side)&&floor(side.below()))return new VanillaTasks.Sequence(new VanillaNavigation(side,2),new VanillaTasks.Use("minecraft:water_bucket",side.below()),new VanillaTasks.Wait(40));}}
        }
        int y=item.equals("minecraft:diamond")?-54:item.equals("minecraft:raw_iron")?16:mc().player.blockPosition().getY();
        return explore(y);
    }
    private static VanillaTask waterBucket(){
        if(count("minecraft:bucket")==0)return ensure("minecraft:bucket",1,new HashSet<>());
        var source=find(s->s.is(Blocks.WATER)&&s.getFluidState().isSource(),20);
        if(source==null)return explore(mc().player.blockPosition().getY());
        return new VanillaTasks.Sequence(new VanillaNavigation(source,3),new VanillaTask(){int n;boolean sent;public boolean tick(){
            close();if(count("minecraft:water_bucket")>0)return true;if(++n>80)throw new IllegalStateException("Water bucket fill failed");if(!equip("minecraft:bucket"))return false;
            Actions.lookAt(Vec3.atCenterOf(source));if(!sent){mc().gameMode.useItem(mc().player,InteractionHand.MAIN_HAND);sent=true;}return false;}});
    }
    private static VanillaTask hunt(String item){
        String entity=item.equals("minecraft:beef")?"minecraft:cow":item.equals("minecraft:feather")?"minecraft:chicken":item.endsWith("_wool")?"minecraft:sheep":item.equals("minecraft:string")?"minecraft:spider":item.equals("minecraft:ender_pearl")?"minecraft:enderman":"minecraft:blaze";
        var targets=entities(48,e->BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals(entity)&&!e.hasCustomName()&&e.isAlive()&&mc().player.hasLineOfSight(e));
        if(targets.isEmpty())return explore(mc().player.blockPosition().getY());
        var target=targets.getFirst();return new VanillaTasks.Sequence(new VanillaTasks.Fight(target,entity.equals("minecraft:blaze")),new VanillaTasks.Collect(null));
    }
    private static VanillaTask explore(int y){
        var p=mc().player.blockPosition();var origin=searchOrigins.computeIfAbsent(mc().level.dimension().location().toString(),k->p);var offset=VanillaMath.spiral(++exploreIndex);
        int depth=mc().level.dimension()==Level.NETHER?Math.max(32,Math.min(100,y)):Math.max(mc().level.getMinBuildHeight()+6,Math.min(mc().level.getMaxBuildHeight()-6,y));
        return new VanillaNavigation(new BlockPos(origin.getX()+(int)offset.x()*16,p.getY()+Integer.signum(depth-p.getY())*Math.min(8,Math.abs(depth-p.getY())),origin.getZ()+(int)offset.z()*16),1.5);
    }
    private static void plan(){
        if(quiet-->0)return;
        switch(stage){
            case "bootstrap" -> {if(need("minecraft:crafting_table",1)||need("minecraft:wooden_pickaxe",1)||need("minecraft:stone_pickaxe",1)||need("minecraft:stone_sword",1)||need("minecraft:furnace",1)||provideFood(32))return;next("iron");}
            case "iron" -> {if(need("minecraft:iron_ingot",35))return;next("equipment");}
            case "equipment" -> {
                for(String s:List.of("iron_pickaxe","iron_sword","iron_helmet","iron_chestplate","iron_leggings","iron_boots","shield","bucket","flint_and_steel","bow"))if(need("minecraft:"+s,1))return;
                if(need("minecraft:arrow",128)||provideFood(192)||need("minecraft:torch",32)||need("minecraft:diamond_pickaxe",1)||need("minecraft:water_bucket",1))return;
                if(blocks()<128){schedule(gather("minecraft:cobblestone"));return;}equipArmor();next("portal");}
            case "portal" -> {if(mc().level.dimension()==Level.NETHER){next("blaze_rods");return;}if(need("minecraft:obsidian",10))return;schedule(buildPortal());}
            case "blaze_rods" -> {if(count("minecraft:blaze_rod")>=8){next("pearls");return;}schedule(hunt("minecraft:blaze_rod"));}
            case "pearls" -> {
                if(count("minecraft:ender_pearl")<16){schedule(hunt("minecraft:ender_pearl"));return;}
                if(mc().level.dimension()==Level.NETHER){var p=portals.get("minecraft:the_nether");if(p==null)p=find(s->s.is(Blocks.NETHER_PORTAL),20);if(p==null)throw new IllegalStateException("Nether entry portal landmark missing");schedule(enter(p));return;}
                next("stronghold");}
            case "stronghold" -> {
                if(need("minecraft:ender_eye",16))return;
                var frame=find(s->s.is(Blocks.END_PORTAL_FRAME),24);if(frame!=null){stronghold=frame;next("end_portal");return;}
                if(stronghold!=null){schedule(new VanillaNavigation(stronghold,2));if(mc().player.blockPosition().distSqr(stronghold)<25){schedule(explore(32));}return;}
                if(!rays.isEmpty()){var ray=rays.getLast();var p=mc().player.position();if(Math.hypot(p.x-ray.x(),p.z-ray.z())<96){double len=Math.hypot(ray.dx(),ray.dz());schedule(new VanillaNavigation(mc().player.blockPosition().offset((int)(-ray.dz()/len*112),0,(int)(ray.dx()/len*112)),2));return;}}
                schedule(new Eye());}
            case "end_portal" -> {
                if(mc().level.dimension()==Level.END){enteredEnd=true;next("dragon");return;}
                var opening=find(s->s.is(Blocks.END_PORTAL),20);if(opening!=null){schedule(enter(opening));return;}
                var frame=find(s->s.is(Blocks.END_PORTAL_FRAME)&&!s.getValue(EndPortalFrameBlock.HAS_EYE),24);
                if(frame==null){schedule(explore(mc().player.blockPosition().getY()));return;}if(need("minecraft:ender_eye",1))return;
                schedule(new VanillaTasks.Sequence(new VanillaNavigation(frame,3),new VanillaTasks.Use("minecraft:ender_eye",frame)));}
            case "dragon" -> dragon();
            case "exit" -> {
                if(mc().level.dimension()!=Level.END&&dragonDied){next("complete");running=false;state="complete";reason="Observed dragon death and returned through exit portal";save();return;}
                var p=find(s->s.is(Blocks.END_PORTAL),24);if(p!=null)schedule(enter(p));else schedule(new VanillaNavigation(new BlockPos(0,65,0),4));}
            case "complete" -> {running=false;state="complete";save();}
            default -> throw new IllegalStateException("Unknown stage");
        }
    }
    private static void equipArmor(){
        close();for(int index=0;index<36;index++){var s=mc().player.getInventory().getItem(index);if(!(s.getItem() instanceof ArmorItem a))continue;
            int target=8-a.getEquipmentSlot().getIndex();if(mc().player.inventoryMenu.getSlot(target).getItem().isEmpty())mc().gameMode.handleInventoryMouseClick(0,index<9?36+index:index,0,ClickType.QUICK_MOVE,mc().player);}
        int shield=slot(s->s.is(Items.SHIELD));if(shield>=0)mc().gameMode.handleInventoryMouseClick(0,shield<9?36+shield:shield,40,ClickType.SWAP,mc().player);
    }
    private static VanillaTask buildPortal(){
        if(portal==null){var origin=site();boolean okay=true;for(int x=0;x<4;x++)for(int y=0;y<5;y++)if(!clear(origin.offset(x,y,0)))okay=false;if(!okay){return explore(mc().player.blockPosition().getY());}portal=origin;}
        var steps=new ArrayList<VanillaTask>();steps.add(new VanillaNavigation(portal,3));
        // Cornerless 10 obsidian frame; dirt corners are legal placement supports.
        for(int x=0;x<4;x++){var p=portal.offset(x,0,0);if(clear(p))steps.add(new VanillaTasks.Place(p,x==0||x==3?null:"minecraft:obsidian"));}
        for(int y=1;y<=3;y++)for(int x:new int[]{0,3}){var p=portal.offset(x,y,0);if(clear(p)){steps.add(new VanillaNavigation(p.offset(0,0,-1),2.5));steps.add(new VanillaTasks.Place(p,"minecraft:obsidian"));}}
        for(int x=0;x<4;x++){var p=portal.offset(x,4,0);if(clear(p)){steps.add(new VanillaNavigation(p.offset(0,0,-1),2.5));steps.add(new VanillaTasks.Place(p,x==0||x==3?null:"minecraft:obsidian"));}}
        steps.add(new VanillaNavigation(portal.offset(1,1,-1),1));steps.add(new VanillaTasks.Use("minecraft:flint_and_steel",portal.offset(1,0,0)));steps.add(enter(portal.offset(1,1,0)));
        return new VanillaTasks.Sequence(steps.toArray(VanillaTask[]::new));
    }
    private static VanillaTask enter(BlockPos p){return new VanillaTask(){String from=mc().level.dimension().location().toString();VanillaNavigation nav=new VanillaNavigation(p,1);int n;
        public String describe(){return "walk through native portal";}
        public boolean tick()throws Exception{
            String dim=mc().level.dimension().location().toString();if(!dim.equals(from)){portals.put(from,p);portals.put(dim,mc().player.blockPosition());return true;}
            if(++n>600)throw new IllegalStateException("Native portal transition timed out");if(!nav.tick())return false;Actions.releaseControls();return false;}
        public void cancel(){nav.cancel();}};}
    private static final class Eye implements VanillaTask {
        int n;Vec3 first,last;UUID seen;final Vec3 launch=mc().player.position();
        public String describe(){return "track thrown Eye of Ender";}
        public boolean tick(){
            close();if(!equip("minecraft:ender_eye"))return false;
            if(n++==0){mc().gameMode.useItem(mc().player,InteractionHand.MAIN_HAND);return false;}
            var eyes=entities(48,e->e instanceof EyeOfEnder&&(seen==null||seen.equals(e.getUUID())));
            if(!eyes.isEmpty()){var e=eyes.getFirst();seen=e.getUUID();if(first==null)first=e.position();last=e.position();}
            if(n<35)return false;if(first==null||last==null||last.distanceTo(first)<1)throw new IllegalStateException("No Eye trajectory observed");
            var ray=new VanillaMath.Ray(launch.x,launch.z,last.x-first.x,last.z-first.z);rays.add(ray);
            for(int i=rays.size()-2;i>=0;i--){var hit=VanillaMath.intersect(rays.get(i),ray);if(hit.isPresent()){stronghold=new BlockPos((int)Math.floor(hit.get().x()),32,(int)Math.floor(hit.get().z()));break;}}
            save();return true;
        }
    }
    private static void dragon(){
        var dragons=entities(160,e->e instanceof EnderDragon);
        if(!dragons.isEmpty()){
            var d=(EnderDragon)dragons.getFirst();if(d.getHealth()<=0||d.isDeadOrDying()){dragonDied=true;next("exit");return;}
            // Crystals must be destroyed before spending arrows on regeneration.
            var crystals=entities(128,e->e instanceof EndCrystal);
            if(!crystals.isEmpty()){var c=crystals.getFirst();if(mc().player.distanceTo(c)<9){schedule(new VanillaNavigation(mc().player.blockPosition().offset(10,0,10),1));return;}schedule(new VanillaTasks.Fight(c,true));return;}
            var bars=find(s->s.is(Blocks.IRON_BARS),24);
            if(bars!=null){schedule(new VanillaTasks.Sequence(new VanillaNavigation(bars,3),new VanillaTasks.Mine(bars)));return;}
            var phase=d.getPhaseManager().getCurrentPhase().getPhase();
            if(phase==EnderDragonPhase.SITTING_SCANNING||phase==EnderDragonPhase.SITTING_ATTACKING||phase==EnderDragonPhase.SITTING_FLAMING){schedule(new VanillaTasks.Fight(d,false));return;}
            if(count("minecraft:arrow")>0){schedule(new VanillaTasks.Fight(d,true));return;}
            schedule(new VanillaNavigation(new BlockPos(0,mc().player.blockPosition().getY(),6),2));
        }else{if(enteredEnd&&dragonDied){next("exit");return;}schedule(new VanillaNavigation(new BlockPos(0,65,0),8));}
    }
    private static void recover()throws Exception{
        String dim=mc().level.dimension().location().toString();
        if(!dim.equals(deathDimension)){
            String edge=VanillaRoute.firstEdge(dim,deathDimension,portals.keySet()).orElseThrow(()->new IllegalStateException("No observed portal route to death dimension"));
            BlockPos p=portals.get(edge);if(task==null)schedule(enter(p));if(task.tick())task=null;return;}
        if(task!=null){if(task.tick())task=null;return;}
        if(mc().player.blockPosition().distSqr(deathPoint)>9){schedule(new VanillaNavigation(deathPoint,2));return;}
        var items=entities(12,e->e instanceof ItemEntity);if(!items.isEmpty()){schedule(new VanillaTasks.Collect(deathPoint));return;}
        // Replenish from actual inventory if drops burned/despawned, never claim recovery without them.
        deathPoint=null;reason="Death site searched; rebuilding missing supplies from actual inventory";
        if(!mc().level.dimension().equals(Level.END))next("bootstrap");save();
    }
}
