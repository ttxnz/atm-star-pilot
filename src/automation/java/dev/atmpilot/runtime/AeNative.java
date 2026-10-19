package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** AE2 19.x native client GUI actions. Never calls server crafting or pattern creation helpers. */
public final class AeNative {
    private AeNative(){}
    private static Class<?> api(String name)throws ClassNotFoundException{return Class.forName(name,true,PilotJobs.class.getClassLoader());}
    private static Object call(Object target,String name)throws ReflectiveOperationException{return target.getClass().getMethod(name).invoke(target);}
    private static void guard(Object menu)throws Exception {
        var mc=Actions.mc();if(menu!=mc.player.containerMenu||mc.screen==null||!mc.player.containerMenu.getCarried().isEmpty())throw new IllegalStateException("AE menu or cursor changed");
        ServerRules.checkScreenAction();Object host=(api("appeng.menu.implementations.PatternProviderMenu").isInstance(menu)||api("appeng.menu.me.crafting.CraftingCPUMenu").isInstance(menu)||Set.of("appeng.menu.implementations.CondenserMenu","com.almostreliable.merequester.requester.RequesterMenu").contains(menu.getClass().getName()))?call(menu,"getBlockEntity"):call(menu,"getHost");
        BlockEntity block=host instanceof BlockEntity b?b:(BlockEntity)call(host,"getBlockEntity");
        if(block.getLevel()!=mc.level)throw new IllegalStateException("AE host dimension changed");
        ServerRules.checkBuild(block.getBlockPos());Actions.checkReach(block.getBlockPos());
    }
    private static List<Slot> slots(Object menu,String name)throws Exception {
        Object semantic=api("appeng.menu.SlotSemantics").getField(name).get(null);
        List<?> values=(List<?>)menu.getClass().getMethod("getSlots",api("appeng.menu.SlotSemantic")).invoke(menu,semantic);
        List<Slot> result=new ArrayList<>();for(Object value:values)result.add((Slot)value);return result;
    }
    private static Slot one(Object menu,String semantic)throws Exception {var list=slots(menu,semantic);if(list.size()!=1)throw new IllegalStateException("Unexpected AE slot mapping: "+semantic);return list.getFirst();}
    private static ItemStack keyItem(Object key)throws Exception {
        if(key==null||!api("appeng.api.stacks.AEItemKey").isInstance(key))throw new IllegalStateException("Only exact item keys are supported");
        return (ItemStack)call(key,"toStack");
    }
    private static Collection<?> entries(Object menu)throws Exception {
        Object repo=call(menu,"getClientRepo");if(repo==null)throw new IllegalStateException("AE entries have not synchronized");
        return (Collection<?>)api("appeng.menu.me.common.IClientRepo").getMethod("getAllEntries").invoke(repo);
    }
    private static long stored(Object menu,PlanModel.Material material)throws Exception {
        long result=0;for(Object e:entries(menu)){Object key=call(e,"getWhat");if(!api("appeng.api.stacks.AEItemKey").isInstance(key))continue;
            if(WorldView.material(keyItem(key)).equals(material))result+=((Number)call(e,"getStoredAmount")).longValue();}return result;
    }
    private static void save(String name,JsonObject value)throws Exception {Files.writeString(PilotMod.CONTROLLER.directory.resolve(name),Jsons.GSON.toJson(value),StandardCharsets.UTF_8);}
    private static JsonObject generic(Object value)throws Exception {
        if(value==null)return Jsons.obj("empty",true);Object key=call(value,"what");long amount=((Number)call(value,"amount")).longValue();if(api("appeng.api.stacks.AEFluidKey").isInstance(key))return Jsons.obj("fluid",call(key,"getId").toString(),"amount",amount);return Jsons.obj("item",WorldView.stack(keyItem(key)),"amount",amount);
    }
    public static final class Inspect implements PilotController.Job {
        public String description(){return "Read the own synchronized AE encoding or crafting GUI";}
        public boolean tick(PilotController c)throws Exception {
            var menu=Actions.mc().player.containerMenu;
            if(Set.of("appeng.menu.me.networktool.NetworkStatusMenu","appeng.menu.me.crafting.CraftingStatusMenu","net.pedroksl.advanced_ae.gui.quantumcomputer.QuantumComputerMenu").contains(menu.getClass().getName())){ServerRules.checkScreenAction();if(Actions.mc().screen==null||!menu.getCarried().isEmpty())throw new IllegalStateException("Network status menu/cursor changed");}else guard(menu);
            JsonObject report=Jsons.obj("observedAt",Instant.now().toString(),"menuId",menu.containerId,"class",menu.getClass().getName());
            if(menu.getClass().getName().equals("appeng.menu.me.items.PatternEncodingTermMenu")){
                report.addProperty("mode",call(menu,"getMode").toString());report.addProperty("substitute",(Boolean)call(menu,"isSubstitute"));report.addProperty("substituteFluids",(Boolean)call(menu,"isSubstituteFluids"));
                JsonArray mapped=new JsonArray();for(String semantic:List.of("CRAFTING_GRID","CRAFTING_RESULT","BLANK_PATTERN","ENCODED_PATTERN"))for(Slot s:slots(menu,semantic))mapped.add(Jsons.obj("semantic",semantic,"slot",s.index,"item",WorldView.stack(s.getItem())));report.add("slots",mapped);
            }else if(menu.getClass().getName().equals("appeng.menu.me.crafting.CraftConfirmMenu")){
                report.addProperty("noCPU",(Boolean)call(menu,"hasNoCPU"));report.addProperty("cpuBytes",((Number)call(menu,"getCpuAvailableBytes")).longValue());Object plan=call(menu,"getPlan");
                if(plan!=null){report.addProperty("usedBytes",((Number)call(plan,"getUsedBytes")).longValue());report.addProperty("simulation",(Boolean)call(plan,"isSimulation"));JsonArray rows=new JsonArray();
                    for(Object e:(List<?>)call(plan,"getEntries"))rows.add(Jsons.obj("item",WorldView.stack(keyItem(call(e,"getWhat"))),"stored",call(e,"getStoredAmount"),"craft",call(e,"getCraftAmount"),"missing",call(e,"getMissingAmount")));report.add("plan",rows);}
            }else if(menu.getClass().getName().equals("appeng.menu.me.crafting.CraftAmountMenu"))report.add("what",generic(call(menu,"getWhatToCraft")));
            else if(menu.getClass().getName().equals("appeng.menu.me.networktool.NetworkStatusMenu")){
                var f=api("appeng.client.gui.me.networktool.NetworkStatusScreen").getDeclaredField("status");f.setAccessible(true);Object status=f.get(Actions.mc().screen);if(status==null)throw new IllegalStateException("Wait for native network status synchronization");
                report.addProperty("channelsUsed",((Number)call(status,"getChannelsUsed")).intValue());report.addProperty("storedPower",((Number)call(status,"getStoredPower")).doubleValue());report.addProperty("maxStoredPower",((Number)call(status,"getMaxStoredPower")).doubleValue());JsonArray rows=new JsonArray();
                for(Object group:(List<?>)call(status,"getGroupedMachines"))rows.add(Jsons.obj("item",WorldView.stack(keyItem(call(group,"getDisplay"))),"count",call(group,"getCount"),"missingChannel",call(group,"isMissingChannel")));report.add("machines",rows);
            }else if(menu.getClass().getName().equals("com.almostreliable.merequester.requester.RequesterMenu")){
                var field=api("com.almostreliable.merequester.client.RequesterScreen").getDeclaredField("requesterReference");field.setAccessible(true);Object reference=field.get(Actions.mc().screen);if(reference==null||!(Boolean)call(reference,"isClientSide"))throw new IllegalStateException("Wait for the native client requester reference");Object requests=call(reference,"getRequestManager");JsonArray rows=new JsonArray();for(int i=0;i<((Number)call(requests,"size")).intValue();i++){Object request=requests.getClass().getMethod("get",int.class).invoke(requests,i);Object key=call(request,"getKey"),status=call(request,"getClientStatus");rows.add(Jsons.obj("index",i,"item",key==null?null:WorldView.stack(keyItem(key)),"enabled",call(request,"getState"),"amount",call(request,"getAmount"),"batch",call(request,"getBatch"),"status",status==null?null:status.getClass().getSimpleName()));}report.add("requests",rows);
            }else if(menu.getClass().getName().equals("appeng.menu.implementations.CondenserMenu")){
                report.addProperty("output",call(menu,"getOutput").toString());report.addProperty("requiredEnergy",menu.getClass().getField("requiredEnergy").getLong(menu));report.addProperty("storedPower",menu.getClass().getField("storedPower").getLong(menu));
            }else if(menu.getClass().getName().equals("appeng.menu.implementations.IOPortMenu")||menu.getClass().getName().equals("com.glodblock.github.extendedae.container.ContainerExIOPort")){
                report.addProperty("operationMode",call(menu,"getOperationMode").toString());report.addProperty("fullMode",call(menu,"getFullMode").toString());
            }else if(menu.getClass().getName().equals("net.pedroksl.advanced_ae.gui.ReactionChamberMenu")){
                report.addProperty("processingTime",menu.getClass().getField("processingTime").getInt(menu));report.addProperty("maxProcessingTime",menu.getClass().getField("maxProcessingTime").getInt(menu));report.addProperty("autoExport",menu.getClass().getField("autoExport").get(menu).toString());report.addProperty("showWarning",menu.getClass().getField("showWarning").getBoolean(menu));JsonArray tanks=new JsonArray();for(Object stack:(List<?>)call(call(menu,"getTank"),"toList"))tanks.add(generic(stack));report.add("tanks",tanks);
            }else if(api("appeng.menu.implementations.PatternProviderMenu").isInstance(menu)){
                report.addProperty("blocking",call(menu,"getBlockingMode").toString());report.addProperty("lockMode",call(menu,"getLockCraftingMode").toString());report.addProperty("lockedReason",call(menu,"getCraftingLockedReason").toString());
            }else if(menu.getClass().getName().equals("net.pedroksl.advanced_ae.gui.quantumcomputer.QuantumComputerMenu")){
                Object cpuList=menu.getClass().getField("cpuList").get(menu);if(cpuList==null)throw new IllegalStateException("Wait for the native quantum CPU list to synchronize");JsonArray rows=new JsonArray();
                for(Object cpu:(List<?>)call(cpuList,"cpus")){Object name=call(cpu,"name"),mode=call(cpu,"mode");rows.add(Jsons.obj("serial",call(cpu,"serial"),"storage",call(cpu,"storage"),"coProcessors",call(cpu,"coProcessors"),"name",name==null?"":call(name,"getString"),"mode",mode==null?"":mode.toString(),"currentJob",generic(call(cpu,"currentJob")),"progress",call(cpu,"progress")));}
                report.add("quantumCpus",rows);
            }else if(Set.of("appeng.menu.me.crafting.CraftingStatusMenu","appeng.menu.me.crafting.CraftingCPUMenu").contains(menu.getClass().getName())){
                Object screen=Actions.mc().screen;Class<?> base=api("appeng.client.gui.me.crafting.CraftingCPUScreen");
                var method=base.getDeclaredMethod("getVisualEntries");method.setAccessible(true);JsonArray rows=new JsonArray();
                for(Object e:(List<?>)method.invoke(screen))rows.add(Jsons.obj("item",WorldView.stack(keyItem(call(e,"getWhat"))),"stored",call(e,"getStoredAmount"),"active",call(e,"getActiveAmount"),"pending",call(e,"getPendingAmount")));
                report.add("entries",rows);
            }else throw new IllegalStateException("Open the own native AE pattern or crafting menu");
            save("last-ae-native-inspection.json",report);return true;
        }
    }
    public static final class Encode implements PilotController.Job {
        private final String recipeId;private final boolean substitute;private Object menu;private RecipeHolder<?> recipe;private PlanModel.Recipe expected;private Slot blank,encoded;private int phase,elapsed,wait,blankBefore;private long before;private PlanModel.Material pattern;
        public Encode(JsonObject data){recipeId=Jsons.str(data,"recipe","");substitute=Jsons.bool(data,"substitute",false);}
        public String description(){return "Encode one native AE crafting pattern: "+recipeId;}
        private void report(String state)throws Exception {save("last-ae-encoding.json",Jsons.obj("observedAt",Instant.now().toString(),"state",state,"recipe",recipeId,"phase",phase,"blankBefore",blankBefore,"blankNow",blank==null?-1:blank.getItem().getCount(),"encoded",encoded==null?null:WorldView.stack(encoded.getItem()),"pattern",pattern));}
        public boolean tick(PilotController c)throws Exception {try{return step(c);}catch(Exception e){try{report("failed_needs_replan");}catch(Exception ignored){}throw e;}}
        private boolean step(PilotController c)throws Exception {
            var mc=Actions.mc();if(menu==null){
                menu=mc.player.containerMenu;guard(menu);if(!menu.getClass().getName().equals("appeng.menu.me.items.PatternEncodingTermMenu"))throw new IllegalStateException("Open the own physical pattern encoding terminal");
                var entry=JeiBridge.entry(recipeId);expected=entry.recipe();
                if(!(entry.raw() instanceof RecipeHolder<?> holder)||!(holder.value() instanceof CraftingRecipe)||!expected.type().equals("minecraft:crafting")||expected.outputs().size()!=1||!expected.deterministic())throw new IllegalArgumentException("One deterministic native crafting recipe is required");recipe=holder;
                blank=one(menu,"BLANK_PATTERN");encoded=one(menu,"ENCODED_PATTERN");if(!encoded.getItem().isEmpty()||!WorldView.material(blank.getItem()).id().equals("ae2:blank_pattern"))throw new IllegalStateException("Load blank patterns and leave encoded output empty");blankBefore=blank.getItem().getCount();
                Object mode=api("appeng.parts.encoding.EncodingMode").getField("CRAFTING").get(null);menu.getClass().getMethod("setMode",api("appeng.parts.encoding.EncodingMode")).invoke(menu,mode);
                menu.getClass().getMethod("setSubstitute",boolean.class).invoke(menu,substitute);menu.getClass().getMethod("setSubstituteFluids",boolean.class).invoke(menu,false);call(menu,"clear");phase=1;wait=6;report("preparing");return false;
            }
            guard(menu);if(++elapsed>600)throw new IllegalStateException("Pattern encoding timed out; do not blindly repeat encode");if(wait-->0)return false;
            if(phase==1){JeiBridge.transfer(recipeId,false);phase=2;wait=10;report("recipe_transferred");return false;}
            if(phase==2){
                var mapped=slots(menu,"CRAFTING_GRID");if(mapped.size()!=9)throw new IllegalStateException("Native crafting grid mapping changed");List<ItemStack> items=mapped.stream().map(s->s.getItem().copy()).toList();CraftingInput input=CraftingInput.of(3,3,items);CraftingRecipe nativeRecipe=(CraftingRecipe)recipe.value();
                if(!nativeRecipe.matches(input,mc.level))throw new IllegalStateException("Visible ghost grid does not match the requested recipe");ItemStack output=nativeRecipe.assemble(input,mc.level.registryAccess());var wanted=expected.outputs().getFirst();
                if(!WorldView.material(output).equals(wanted.material())||output.getCount()!=wanted.count())throw new IllegalStateException("Visible crafting result differs from the live recipe");
                if(blank.getItem().getCount()!=blankBefore||!encoded.getItem().isEmpty())throw new IllegalStateException("Pattern slots changed before encoding");call(menu,"encode");phase=3;wait=8;report("encode_sent_once");return false;
            }
            if(phase==3){
                ItemStack stack=encoded.getItem();if(stack.isEmpty())return false;Object details=api("appeng.api.crafting.PatternDetailsHelper").getMethod("decodePattern",ItemStack.class,net.minecraft.world.level.Level.class).invoke(null,stack,mc.level);
                if(details==null||!details.getClass().getName().equals("appeng.crafting.pattern.AECraftingPattern"))throw new IllegalStateException("Server returned another pattern type");
                var encodedData=JsonParser.parseString(WorldView.material(stack).components()).getAsJsonObject().getAsJsonObject("ae2:encoded_crafting_pattern");
                if(encodedData==null||Jsons.bool(encodedData,"canSubstitute",false)!=substitute)throw new IllegalStateException("Server pattern substitution setting differs from the requested native mode");
                List<?> outputs=(List<?>)api("appeng.api.crafting.IPatternDetails").getMethod("getOutputs").invoke(details);var wanted=expected.outputs().getFirst();
                if(outputs.size()!=1||!WorldView.material(keyItem(call(outputs.getFirst(),"what"))).equals(wanted.material())||((Number)call(outputs.getFirst(),"amount")).longValue()!=wanted.count())throw new IllegalStateException("Encoded pattern output differs from request");
                List<?> sparse=(List<?>)call(details,"getSparseInputs");var mapped=slots(menu,"CRAFTING_GRID");if(sparse.size()!=9)throw new IllegalStateException("Encoded input grid has another shape");
                for(int i=0;i<9;i++){Object item=sparse.get(i);ItemStack shown=mapped.get(i).getItem();if(item==null){if(!shown.isEmpty())throw new IllegalStateException("Encoded pattern lost an input");}
                    else if(shown.isEmpty()||!WorldView.material(keyItem(call(item,"what"))).equals(WorldView.material(shown))||((Number)call(item,"amount")).longValue()!=shown.getCount())throw new IllegalStateException("Encoded pattern inputs differ from the visible recipe");}
                if(blank.getItem().getCount()!=blankBefore-1||stack.getCount()!=1)throw new IllegalStateException("Blank pattern consumption differs from one");pattern=WorldView.material(stack);before=WorldView.count(pattern);Actions.click(mc.player.containerMenu.containerId,encoded.index,0,ClickType.QUICK_MOVE);phase=4;wait=6;report("collect_sent_once");return false;
            }
            if(phase==4){if(!encoded.getItem().isEmpty()||WorldView.count(pattern)!=before+1)throw new IllegalStateException("Encoded pattern collection was not verified");report("completed_verified");return true;}throw new IllegalStateException("Invalid pattern phase");
        }
        public void cancel(){try{report("interrupted_needs_replan");}catch(Exception ignored){}}
    }
    public static final class Request implements PilotController.Job {
        private final PlanModel.Material material;private final int count,timeout,minimumCpuBytes;private final boolean resumePlan;private final boolean waitForCompletion;private BlockPos hostPosition;private int phase,elapsed,wait,cpuCycles,submitRetries;private String lastSubmitError="";private long baseline,target,playerBefore,selectedCpuBytes;private boolean sent,initialized;
        public Request(JsonObject data){material=new PlanModel.Material(Jsons.str(data,"item",""),Jsons.str(data,"components",""));count=Jsons.integer(data,"count",1);timeout=Jsons.integer(data,"timeoutTicks",12000);minimumCpuBytes=Jsons.integer(data,"minimumCpuBytes",0);resumePlan=Jsons.bool(data,"resumePlan",false);waitForCompletion=Jsons.bool(data,"waitForCompletion",true);if(count<1||count>1000000||timeout<20||timeout>72000||minimumCpuBytes<0||minimumCpuBytes>1000000000)throw new IllegalArgumentException("Invalid AE request");}
        public String description(){return "Request native AE crafting of "+count+" "+material.id();}
        private void report(String state,Long current)throws Exception {save("last-ae-request.json",Jsons.obj("observedAt",Instant.now().toString(),"state",state,"item",material.id(),"components",material.components(),"requested",count,"stockBefore",baseline,"targetStock",target,"stockNow",current,"playerBefore",playerBefore,"waitForCompletion",waitForCompletion,"minimumCpuBytes",minimumCpuBytes,"selectedCpuBytes",selectedCpuBytes,"phase",phase,"startSent",sent,"submitRetries",submitRetries,"lastSubmitError",lastSubmitError,"ticks",elapsed));}
        public boolean tick(PilotController c)throws Exception {try{return step(c);}catch(Exception e){try{report("failed_needs_replan",null);}catch(Exception ignored){}throw e;}}
        private boolean step(PilotController c)throws Exception {
            var mc=Actions.mc();Object menu=mc.player.containerMenu;guard(menu);Object host=call(menu,"getHost");BlockEntity block=host instanceof BlockEntity b?b:(BlockEntity)call(host,"getBlockEntity");
            if(hostPosition==null)hostPosition=block.getBlockPos();else if(!hostPosition.equals(block.getBlockPos()))throw new IllegalStateException("AE request moved to another host");
            if(!initialized){initialized=true;if(resumePlan){
                JsonObject checkpoint=JsonParser.parseString(Files.readString(PilotMod.CONTROLLER.directory.resolve("last-ae-request.json"),StandardCharsets.UTF_8)).getAsJsonObject();
                if(!menu.getClass().getName().equals("appeng.menu.me.crafting.CraftConfirmMenu")||Jsons.bool(checkpoint,"startSent",true)||Jsons.integer(checkpoint,"phase",-1)!=2||!Jsons.str(checkpoint,"item","").equals(material.id())||!Jsons.str(checkpoint,"components","").equals(material.components())||Jsons.integer(checkpoint,"requested",-1)!=count)throw new IllegalStateException("Only the matching recorded, unsubmitted confirmation plan may resume");
                baseline=checkpoint.get("stockBefore").getAsLong();target=baseline+count;playerBefore=WorldView.count(material);phase=2;
            }}
            if(++elapsed>timeout)throw new IllegalStateException("AE job timed out; inspect the active CPU before requesting again");if(wait-->0)return false;
            if(phase==0){
                if(!api("appeng.menu.me.common.MEStorageMenu").isInstance(menu))throw new IllegalStateException("Open the own physical AE terminal first");
                baseline=stored(menu,material);target=baseline+count;playerBefore=WorldView.count(material);Object match=null;
                for(Object e:entries(menu)){Object key=call(e,"getWhat");if(!api("appeng.api.stacks.AEItemKey").isInstance(key))continue;if(WorldView.material(keyItem(key)).equals(material)&&(Boolean)call(e,"isCraftable")){if(match!=null)throw new IllegalStateException("Duplicate craftable entries");match=e;}}
                if(match==null)throw new IllegalStateException("AE has no synchronized crafting pattern for this exact item");Object action=api("appeng.helpers.InventoryAction").getField("AUTO_CRAFT").get(null);
                menu.getClass().getMethod("handleInteraction",long.class,api("appeng.helpers.InventoryAction")).invoke(menu,((Number)call(match,"getSerial")).longValue(),action);phase=1;wait=5;report("amount_menu_requested",null);return false;
            }
            if(phase==1){if(!menu.getClass().getName().equals("appeng.menu.me.crafting.CraftAmountMenu"))return false;Object what=call(menu,"getWhatToCraft");if(!WorldView.material(keyItem(call(what,"what"))).equals(material))throw new IllegalStateException("AE amount menu targets another item");
                menu.getClass().getMethod("confirm",int.class,boolean.class,boolean.class).invoke(menu,count,false,false);phase=2;wait=8;report("plan_requested",null);return false;}
            if(phase==2){if(!menu.getClass().getName().equals("appeng.menu.me.crafting.CraftConfirmMenu"))return false;Object plan=call(menu,"getPlan");if(plan==null)return false;
                if((Boolean)call(plan,"isSimulation"))throw new IllegalStateException("AE reports missing resources; native job was not started");boolean targetPresent=false;for(Object e:(List<?>)call(plan,"getEntries")){
                    if(((Number)call(e,"getMissingAmount")).longValue()>0)throw new IllegalStateException("AE crafting plan contains missing inputs");Object key=call(e,"getWhat");if(api("appeng.api.stacks.AEItemKey").isInstance(key)&&WorldView.material(keyItem(key)).equals(material)&&((Number)call(e,"getCraftAmount")).longValue()>=count)targetPresent=true;}
                if(!targetPresent)throw new IllegalStateException("AE plan does not include the requested exact item and amount");
                if((Boolean)call(menu,"hasNoCPU"))throw new IllegalStateException("AE has no available crafting CPU");long bytes=((Number)call(plan,"getUsedBytes")).longValue();long available=((Number)call(menu,"getCpuAvailableBytes")).longValue();
                // AE's default Automatic entry reports zero bytes. Select an actual synchronized CPU first.
                if(available<Math.max(bytes,minimumCpuBytes)){if(cpuCycles++>=(minimumCpuBytes>0?8:2))throw new IllegalStateException("No available CPU meets the observed crafting storage requirement");menu.getClass().getMethod("cycleSelectedCPU",boolean.class).invoke(menu,false);wait=20;report("selecting_cpu",null);return false;}
                selectedCpuBytes=available;
                call(menu,"startJob");sent=true;phase=3;wait=8;report("start_sent_once",null);return false;}
            if(phase==3){
                if(menu.getClass().getName().equals("appeng.menu.me.crafting.CraftConfirmMenu")){
                    Object sync=menu.getClass().getField("submitError").get(menu);
                    if(sync!=null){Object result=call(sync,"result"),error=call(result,"errorCode");
                        if(error!=null){lastSubmitError=error.toString();sent=false;report("server_rejected_not_started",null);
                            if(Set.of("CPU_OFFLINE","CPU_BUSY","NO_CPU_FOUND","NO_SUITABLE_CPU_FOUND").contains(lastSubmitError)&&submitRetries<3&&mc.screen.getClass().getName().equals("appeng.client.gui.me.crafting.CraftErrorScreen")){
                                submitRetries++;var back=api("appeng.client.gui.AESubScreen").getDeclaredMethod("returnToParent");back.setAccessible(true);back.invoke(mc.screen);call(menu,"replan");phase=2;cpuCycles=0;wait=20;return false;
                            }
                            throw new IllegalStateException("AE server rejected the submission: "+lastSubmitError+"; no crafting job was accepted");
                        }
                    }
                    return false;
                }
                if(!api("appeng.menu.me.common.MEStorageMenu").isInstance(menu))return false;long now=stored(menu,material);
                if(WorldView.count(material)!=playerBefore)throw new IllegalStateException("Player inventory changed during autonomous AE crafting");if(now>=target){report("completed_verified",now);return true;}if(!waitForCompletion){report("submitted_running_not_completed",now);return true;}wait=4;return false;}
            throw new IllegalStateException("Invalid AE request phase");
        }
        public void cancel(){try{report("interrupted_needs_replan",null);}catch(Exception ignored){}}
    }
}
