package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Fixed AE2 19.x client adapter. Only synchronized terminal entries and ordinary native clicks. */
public final class AeAccess {
    private AeAccess(){}
    private static Class<?> api(String name)throws ClassNotFoundException{return Class.forName(name,true,PilotJobs.class.getClassLoader());}
    private static Object call(Object target,String name)throws ReflectiveOperationException{return target.getClass().getMethod(name).invoke(target);}
    private static boolean isTerminal(Object host){
        String name=host.getClass().getName();
        return name.equals("appeng.parts.reporting.ItemTerminalPart")||name.equals("appeng.parts.reporting.CraftingTerminalPart")||name.equals("appeng.parts.encoding.PatternEncodingTerminalPart");
    }
    private static Object terminalMenu()throws ReflectiveOperationException{
        Object menu=Actions.mc().player.containerMenu;
        if(!api("appeng.menu.me.common.MEStorageMenu").isInstance(menu)||!isTerminal(call(menu,"getHost")))
            throw new IllegalStateException("Open a physical AE item/crafting terminal; configuration and portable menus are not supported");
        return menu;
    }
    private static void guard(Object menu)throws ReflectiveOperationException{
        if(menu!=Actions.mc().player.containerMenu)throw new IllegalStateException("AE menu changed");
        Object host=call(menu,"getHost");
        if(!isTerminal(host))throw new IllegalStateException("Unsupported AE host");
        BlockEntity block=(BlockEntity)call(host,"getBlockEntity");
        if(block.getLevel()!=Actions.mc().level)throw new IllegalStateException("AE host dimension changed");
        ServerRules.checkOpen(block.getBlockPos());Actions.checkReach(block.getBlockPos());
    }
    private static Collection<?> entries(Object menu)throws ReflectiveOperationException{
        Object repo=call(menu,"getClientRepo");
        if(repo==null)throw new IllegalStateException("AE terminal has not received its inventory yet");
        return (Collection<?>)api("appeng.menu.me.common.IClientRepo").getMethod("getAllEntries").invoke(repo);
    }
    private static ItemStack item(Object entry)throws ReflectiveOperationException{
        Object key=call(entry,"getWhat");
        return api("appeng.api.stacks.AEItemKey").isInstance(key)?(ItemStack)call(key,"toStack"):ItemStack.EMPTY;
    }
    private static long amount(Object entry)throws ReflectiveOperationException{return ((Number)call(entry,"getStoredAmount")).longValue();}
    private static long serial(Object entry)throws ReflectiveOperationException{return ((Number)call(entry,"getSerial")).longValue();}
    private static void interact(Object menu,long serial,String action)throws ReflectiveOperationException{
        guard(menu);
        Object value=api("appeng.helpers.InventoryAction").getField(action).get(null);
        api("appeng.menu.me.common.MEStorageMenu").getMethod("handleInteraction",long.class,api("appeng.helpers.InventoryAction")).invoke(menu,serial,value);
    }
    private static void save(String file,JsonObject report)throws Exception{
        Files.writeString(PilotMod.CONTROLLER.directory.resolve(file),Jsons.GSON.toJson(report),StandardCharsets.UTF_8);
    }
    public static final class Inspect implements PilotController.Job {
        private final int radius;private final String filter;
        public Inspect(JsonObject data){radius=Jsons.integer(data,"radius",16);filter=Jsons.str(data,"filter","");if(radius<0||radius>24)throw new IllegalArgumentException("AE inspection radius must be 0..24");}
        public String description(){return "Read synchronized AE terminal entries and loaded cable parts";}
        public boolean tick(PilotController controller)throws Exception{
            var mc=Actions.mc();JsonObject report=Jsons.obj("observedAt",Instant.now().toString(),"dimension",mc.level.dimension().location().toString(),"menuId",mc.player.containerMenu.containerId);
            JsonArray parts=new JsonArray();Class<?> hostType=api("appeng.api.parts.IPartHost");
            BlockPos center=mc.player.blockPosition();
            for(BlockPos pos:BlockPos.betweenClosed(center.offset(-radius,-4,-radius),center.offset(radius,5,radius))){
                if(!LoadedWorld.has(pos))continue;
                BlockEntity entity=mc.level.getBlockEntity(pos);if(!hostType.isInstance(entity))continue;
                Object cable=hostType.getMethod("getPart",Direction.class).invoke(entity,new Object[]{null});
                if(cable!=null){Object cableItem=api("appeng.api.parts.IPart").getMethod("getPartItem").invoke(cable);JsonObject cableRecord=Jsons.obj("x",pos.getX(),"y",pos.getY(),"z",pos.getZ(),"face","center","item",cableItem instanceof net.minecraft.world.item.Item ci?BuiltInRegistries.ITEM.getKey(ci).toString():cableItem.toString(),"class",cable.getClass().getName());JsonObject sides=new JsonObject();for(Direction side:Direction.values())if((Boolean)callDirection(cable,"isConnected",side))sides.addProperty(side.getName(),((Number)callDirection(cable,"getChannelsOnSide",side)).intValue());cableRecord.add("connectedChannels",sides);parts.add(cableRecord);}
                for(Direction face:Direction.values()){
                    Object part=hostType.getMethod("getPart",Direction.class).invoke(entity,face);if(part==null)continue;
                    Object partItem=api("appeng.api.parts.IPart").getMethod("getPartItem").invoke(part);
                    String id=partItem instanceof net.minecraft.world.item.Item value?BuiltInRegistries.ITEM.getKey(value).toString():partItem.toString();
                    parts.add(Jsons.obj("x",pos.getX(),"y",pos.getY(),"z",pos.getZ(),"face",face.getName(),"item",id,"class",part.getClass().getName(),"terminal",isTerminal(part)));
                    JsonObject record=parts.get(parts.size()-1).getAsJsonObject();
                    for(String property:List.of("isPowered","isActive","isMissingChannel")){
                        try{record.addProperty(property,(Boolean)call(part,property));}catch(ReflectiveOperationException ignored){}
                    }
                }
            }
            report.add("parts",parts);JsonArray inventory=new JsonArray();
            if(api("appeng.menu.me.common.MEStorageMenu").isInstance(mc.player.containerMenu)){
                for(Object entry:entries(mc.player.containerMenu)){
                    ItemStack stack=item(entry);if(stack.isEmpty())continue;
                    JsonObject value=WorldView.stack(stack);String id=value.get("id").getAsString();
                    if(!filter.isEmpty()&&!id.contains(filter))continue;
                    value.addProperty("stored",amount(entry));value.addProperty("serial",serial(entry));value.addProperty("craftable",(Boolean)call(entry,"isCraftable"));inventory.add(value);
                }
            }
            report.add("entries",inventory);save("last-ae-inspection.json",report);controller.note("AE inspection saved: "+parts.size()+" parts, "+inventory.size()+" synchronized item entries");return true;
        }
    }
    private static Object callDirection(Object value,String method,Direction side)throws ReflectiveOperationException{return value.getClass().getMethod(method,Direction.class).invoke(value,side);}
    public static final class Open implements PilotController.Job {
        private final BlockPos position;private final Direction face;private int elapsed,initial;
        public Open(JsonObject data){position=Actions.pos(data);face=Direction.byName(Jsons.str(data,"face",""));if(face==null)throw new IllegalArgumentException("AE terminal face is required");}
        public String description(){return "Open observed AE terminal at "+position.toShortString()+" "+face.getName();}
        public boolean tick(PilotController controller)throws Exception{
            var mc=Actions.mc();
            if(elapsed++==0){
                if(mc.screen!=null)throw new IllegalStateException("Close the current screen before opening an AE terminal");
                Object entity=mc.level.getBlockEntity(position);
                Class<?> host=api("appeng.api.parts.IPartHost");
                if(!host.isInstance(entity))throw new IllegalStateException("Observed AE host is no longer present");
                Object part=host.getMethod("getPart",Direction.class).invoke(entity,face);
                if(part==null||!isTerminal(part))throw new IllegalStateException("Observed face is not an item/crafting terminal");
                initial=mc.player.containerMenu.containerId;Actions.useBlock(position,face);return false;
            }
            if(mc.player.containerMenu.containerId!=initial){terminalMenu();controller.remember(mc.level.dimension().location()+"@"+position.toShortString()+"/"+face.getName());return true;}
            if(elapsed>300)throw new IllegalStateException("AE terminal did not open");return false;
        }
    }
    public static final class ClearGrid implements PilotController.Job {
        private Object menu;private int elapsed;
        public String description(){return "Return the own AE crafting grid through its native clear button";}
        public boolean tick(PilotController controller)throws Exception{
            var mc=Actions.mc();ServerRules.checkScreenAction();
            if(menu==null){
                menu=terminalMenu();guard(menu);
                if(!menu.getClass().getName().equals("appeng.menu.me.items.CraftingTermMenu")||!mc.player.containerMenu.getCarried().isEmpty())
                    throw new IllegalStateException("An own crafting terminal with an empty cursor is required");
                menu.getClass().getMethod("clearCraftingGrid").invoke(menu);return false;
            }
            guard(menu);Object semantic=api("appeng.menu.SlotSemantics").getField("CRAFTING_GRID").get(null);
            List<?> slots=(List<?>)menu.getClass().getMethod("getSlots",api("appeng.menu.SlotSemantic")).invoke(menu,semantic);
            if(slots.size()!=9)throw new IllegalStateException("The native AE crafting grid changed");
            if(slots.stream().allMatch(value->((Slot)value).getItem().isEmpty()))return true;
            if(++elapsed>100)throw new IllegalStateException("The native AE clear action did not empty the grid");return false;
        }
    }
    public static final class Attach implements PilotController.Job {
        private final BlockPos position;private final Direction face;private final String item;private int elapsed,phase;private boolean clicked;
        public Attach(JsonObject data){
            position=Actions.pos(data);face=Direction.byName(Jsons.str(data,"face",""));item=Jsons.str(data,"item","");
            if(face==null||!Set.of("ae2:crafting_terminal","ae2:terminal","ae2:pattern_encoding_terminal","ae2:storage_bus","ae2:import_bus","ae2:export_bus","ae2:cable_interface").contains(item))throw new IllegalArgumentException("An explicit supported AE part and face are required");
        }
        public String description(){return "Attach "+item+" to own AE cable at "+position.toShortString()+" "+face.getName();}
        public boolean tick(PilotController controller)throws Exception{
            var mc=Actions.mc();ServerRules.checkBuild(position);Actions.checkReach(position);
            if(!LoadedWorld.has(position))throw new IllegalStateException("AE cable chunk is not loaded");
            Object host=mc.level.getBlockEntity(position);Class<?> type=api("appeng.api.parts.IPartHost");
            if(!type.isInstance(host))throw new IllegalStateException("Place and observe the own AE cable bus before attaching parts");
            Object existing=type.getMethod("getPart",Direction.class).invoke(host,face);
            if(existing!=null){
                Object partItem=api("appeng.api.parts.IPart").getMethod("getPartItem").invoke(existing);
                if(partItem instanceof net.minecraft.world.item.Item nativeItem&&BuiltInRegistries.ITEM.getKey(nativeItem).toString().equals(item))return true;
                throw new IllegalStateException("That AE face is occupied by another part");
            }
            if(elapsed++>100)throw new IllegalStateException("AE part placement was not confirmed");
            if(phase==0){
                Actions.closeMenu();int found=-1;
                for(int i=0;i<36;i++)if(BuiltInRegistries.ITEM.getKey(mc.player.getInventory().getItem(i).getItem()).toString().equals(item)){found=i;break;}
                if(found<0)throw new IllegalStateException("Missing AE part: "+item);
                if(found>=9){Actions.click(mc.player.inventoryMenu.containerId,found,8,ClickType.SWAP);Actions.select(8);}else Actions.select(found);
                phase=1;return false;
            }
            if(!clicked&&elapsed>=5){
                if(!BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).toString().equals(item))throw new IllegalStateException("Selected AE part changed");
                Actions.useBlock(position,face);clicked=true;
            }
            return false;
        }
    }
    /** Return explicitly named stackable building materials through the own terminal's native player slots. */
    public static final class Deposit implements PilotController.Job {
        private final Set<String> ids=new LinkedHashSet<>();private Object menu;private int pending=-1,wait;
        private ItemStack sent=ItemStack.EMPTY;private final JsonArray moved=new JsonArray();
        public Deposit(JsonObject data){
            var list=data.getAsJsonArray("items");if(list==null||list.isEmpty()||list.size()>64)throw new IllegalArgumentException("Explicit material IDs are required");
            for(var value:list)if(!ids.add(value.getAsString()))throw new IllegalArgumentException("Duplicate deposit material");
        }
        public String description(){return "Return named stackable materials to own AE through ordinary quick-move clicks";}
        public boolean tick(PilotController controller)throws Exception{
            var mc=Actions.mc();if(menu==null){menu=terminalMenu();if(!menu.getClass().getName().equals("appeng.menu.me.items.CraftingTermMenu"))throw new IllegalStateException("Use the own physical AE crafting terminal for material cleanup");}
            guard(menu);Object host=call(menu,"getHost");ServerRules.checkBuild(((BlockEntity)call(host,"getBlockEntity")).getBlockPos());
            if(!mc.player.containerMenu.getCarried().isEmpty())throw new IllegalStateException("Return the current cursor before material cleanup");
            if(pending>=0){
                if(mc.player.containerMenu.getSlot(pending).getItem().isEmpty()){moved.add(WorldView.stack(sent));pending=-1;wait=0;}
                else{if(++wait>100)throw new IllegalStateException("AE material return was not confirmed; no pending click was repeated");return false;}
            }
            for(int i=0;i<mc.player.containerMenu.slots.size();i++){
                Slot slot=mc.player.containerMenu.getSlot(i);ItemStack stack=slot.getItem();
                if(slot.container!=mc.player.getInventory()||stack.isEmpty())continue;
                String id=BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();if(!ids.contains(id))continue;
                if(stack.getMaxStackSize()<2||id.contains("backpack"))throw new IllegalStateException("Equipment and backpacks cannot be auto-deposited");
                sent=stack.copy();pending=i;Actions.click(mc.player.containerMenu.containerId,i,0,ClickType.QUICK_MOVE);return false;
            }
            save("last-ae-deposit.json",Jsons.obj("observedAt",Instant.now().toString(),"menuId",mc.player.containerMenu.containerId,"materials",moved));return true;
        }
    }
    public static final class Withdraw implements PilotController.Job {
        private final PlanModel.Material material;private final int requested;private Object menu;private int phase,wait,elapsed,delivered,slot=-1;private long before,entrySerial;
        public Withdraw(JsonObject data){material=new PlanModel.Material(Jsons.str(data,"item",""),Jsons.str(data,"components",""));requested=Jsons.integer(data,"count",1);if(requested<1||requested>64)throw new IllegalArgumentException("AE withdrawal count must be 1..64");}
        public String description(){return "Withdraw exactly "+requested+" "+material.id()+" from authorized AE storage";}
        private int destination(ItemStack prototype){
            var mc=Actions.mc();int empty=-1;
            for(int i=0;i<mc.player.containerMenu.slots.size();i++){
                Slot target=mc.player.containerMenu.slots.get(i);
                if(target.container!=mc.player.getInventory()||target.getContainerSlot()==mc.player.getInventory().selected)continue;
                ItemStack stack=target.getItem();if(!stack.isEmpty()&&!WorldView.material(stack).equals(material))continue;
                if(!target.mayPlace(prototype))continue;
                int space=Math.min(prototype.getMaxStackSize(),target.getMaxStackSize(prototype))-stack.getCount();
                if(space<requested)continue;
                if(!stack.isEmpty())return i;
                if(empty<0)empty=i;
            }
            if(empty<0)throw new IllegalStateException("No non-selected player slot has room for this exact withdrawal");return empty;
        }
        public boolean tick(PilotController controller)throws Exception{
            var mc=Actions.mc();
            if(menu==null){
                menu=terminalMenu();guard(menu);
                if(!mc.player.containerMenu.getCarried().isEmpty())throw new IllegalStateException("AE withdrawal requires an empty cursor");
                before=WorldView.count(material);Object match=null;
                for(Object entry:entries(menu)){ItemStack stack=item(entry);if(!stack.isEmpty()&&WorldView.material(stack).equals(material)){
                    if(match!=null)throw new IllegalStateException("Duplicate synchronized AE entries; inspect before retrying");match=entry;
                }}
                if(match==null||amount(match)<requested)throw new IllegalStateException("AE storage lacks the requested exact item and count");
                slot=destination(item(match));
                entrySerial=serial(match);interact(menu,entrySerial,"PICKUP_OR_SET_DOWN");phase=1;wait=4;return false;
            }
            guard(menu);if(++elapsed>600)throw new IllegalStateException("AE withdrawal timed out; inspect the visible cursor before any retry");
            if(wait-->0)return false;ItemStack carried=mc.player.containerMenu.getCarried();
            if(phase==1){
                if(carried.isEmpty())return false;
                if(!WorldView.material(carried).equals(material)||carried.getCount()<requested)throw new IllegalStateException("AE pickup differs from the inspected stock; cursor left visible");
                phase=2;
            }
            if(phase==2){
                long gained=WorldView.count(material)-before;
                if(gained!=delivered)throw new IllegalStateException("Inventory changed during AE withdrawal; inspect before retrying");
                if(delivered<requested){
                    if(carried.isEmpty()||!WorldView.material(carried).equals(material))throw new IllegalStateException("AE carried item unexpectedly changed");
                    int remaining=requested-delivered;
                    if(carried.getCount()==remaining){Actions.click(mc.player.containerMenu.containerId,slot,0,ClickType.PICKUP);delivered=requested;}
                    else {
                        for(int clicks=0;clicks<16&&delivered<requested;clicks++){
                            carried=mc.player.containerMenu.getCarried();
                            if(carried.isEmpty()||!WorldView.material(carried).equals(material)||carried.getCount()<requested-delivered)
                                throw new IllegalStateException("AE cursor changed during bounded transfer; inspect before retrying");
                            if(carried.getCount()==requested-delivered){Actions.click(mc.player.containerMenu.containerId,slot,0,ClickType.PICKUP);delivered=requested;}
                            else {Actions.click(mc.player.containerMenu.containerId,slot,1,ClickType.PICKUP);delivered++;}
                        }
                    }
                    wait=3;return false;
                }
                if(!carried.isEmpty()){interact(menu,-1,"PICKUP_OR_SET_DOWN");phase=3;wait=4;return false;}
                phase=3;
            }
            if(phase==3){
                if(!carried.isEmpty())return false;
                long gained=WorldView.count(material)-before;
                if(gained!=requested)throw new IllegalStateException("AE withdrawal final count mismatch");
                save("last-ae-withdrawal.json",Jsons.obj("observedAt",Instant.now().toString(),"item",material.id(),"components",material.components(),"requested",requested,"inventoryBefore",before,"inventoryAfter",WorldView.count(material),"cursorEmpty",true,"menuId",mc.player.containerMenu.containerId));
                return true;
            }
            return false;
        }
    }
}
