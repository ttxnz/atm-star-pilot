package dev.atmpilot;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import java.util.*;
import static dev.atmpilot.PlanModel.*;

public final class WorldView {
    private WorldView() {}
    public static Material material(ItemStack stack) {
        String components = "";
        var mc = Minecraft.getInstance();
        if (mc.level != null && !stack.getComponentsPatch().isEmpty()) {
            var encoded = DataComponentPatch.CODEC.encodeStart(
                RegistryOps.create(JsonOps.INSTANCE,mc.level.registryAccess()),stack.getComponentsPatch());
            components = encoded.result().map(Jsons::sorted).map(JsonElement::toString).orElseThrow(
                () -> new IllegalArgumentException("Cannot encode item components"));
        }
        return new Material(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),components);
    }
    public static JsonObject stack(ItemStack stack) {
        if (stack.isEmpty()) return Jsons.obj("id","minecraft:air","count",0);
        return Jsons.obj("material",material(stack),"id",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                         "count",stack.getCount(),"name",stack.getHoverName().getString(),"max",stack.getMaxStackSize());
    }
    public static Map<Material,Long> inventory() {
        var mc = Minecraft.getInstance();
        Map<Material,Long> result = new LinkedHashMap<>();
        if (mc.player != null) {
            for (int i=0;i<36;i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (!stack.isEmpty()) result.merge(material(stack),(long)stack.getCount(),Long::sum);
            }
        }
        return result;
    }
    public static long count(Material target) { return inventory().getOrDefault(target,0L); }
    public static JsonObject state(PilotController controller) {
        var mc = Minecraft.getInstance();
        JsonObject result = Jsons.obj("version","0.1.0","connected",mc.player!=null&&mc.level!=null,
            "catalog",OptionalJei.status(),"controller",controller.status());
        result.add("vanilla",dev.atmpilot.vanilla.VanillaRun.status());
        result.add("deathRecovery",DeathRecovery.status());
        result.add("survivalReflex",SurvivalReflex.status());
        try{result.add("serverRules",ServerRules.snapshot());}catch(IllegalStateException e){result.addProperty("serverRulesError",e.getMessage());}
        if (mc.player==null || mc.level==null) return result;
        var p = mc.player;
        result.add("player",Jsons.obj("x",p.getX(),"y",p.getY(),"z",p.getZ(),"yaw",p.getYRot(),"pitch",p.getXRot(),
            "health",p.getHealth(),"food",p.getFoodData().getFoodLevel(),"alive",p.isAlive(),
            "dimension",mc.level.dimension().location().toString(),"selected",p.getInventory().selected,
            "screen",mc.screen==null?null:mc.screen.getClass().getName()));
        JsonArray inventory = new JsonArray();
        for(int i=0;i<p.getInventory().getContainerSize();i++) {
            JsonObject entry=stack(p.getInventory().getItem(i)); entry.addProperty("slot",i); inventory.add(entry);
        }
        result.add("inventory",inventory);
        var menu=p.containerMenu;
        java.lang.reflect.Method aeSemantic=null;
        if(menu.getClass().getName().startsWith("appeng.menu."))try{aeSemantic=menu.getClass().getMethod("getSlotSemantic",net.minecraft.world.inventory.Slot.class);}catch(ReflectiveOperationException ignored){}
        JsonArray slots=new JsonArray();
        for(int i=0;i<menu.slots.size();i++) {
            var slot=menu.slots.get(i); JsonObject entry=stack(slot.getItem());
            entry.addProperty("slot",i); entry.addProperty("playerInventory",slot.container==p.getInventory()
                ||(slot instanceof SlotItemHandler wrapped&&wrapped.getItemHandler() instanceof InvWrapper inv&&inv.getInv()==p.getInventory()));
            entry.addProperty("containerSlot",slot.getContainerSlot());
            entry.addProperty("slotLimit",slot.getMaxStackSize(slot.getItem()));
            entry.addProperty("guiX",slot.x);entry.addProperty("guiY",slot.y);
            if(aeSemantic!=null)try{Object semantic=aeSemantic.invoke(menu,slot);if(semantic!=null)entry.addProperty("semantic",semantic.getClass().getMethod("id").invoke(semantic).toString());}catch(ReflectiveOperationException ignored){}
            if(slot.getClass().getName().startsWith("mekanism.common.inventory.container.slot."))try{
                Class<?> nativeSlot=Class.forName("mekanism.common.inventory.container.slot.InventoryContainerSlot");
                if(nativeSlot.isInstance(slot)){
                    Object inventorySlot=nativeSlot.getMethod("getInventorySlot").invoke(slot);
                    entry.addProperty("inventorySlotClass",inventorySlot.getClass().getName());
                    entry.addProperty("mekSlotType",nativeSlot.getMethod("getSlotType").invoke(slot).toString());
                }
            }catch(ReflectiveOperationException ignored){}
            entry.addProperty("slotClass",slot.getClass().getName()); slots.add(entry);
        }
        JsonObject menuView=Jsons.obj("id",menu.containerId,"stateId",menu.getStateId(),
            "class",menu.getClass().getName(),"carried",stack(menu.getCarried()),"slots",slots);
        if(menu.getClass().getName().startsWith("net.p3pp3rf1y.sophisticated")){
            try{
                JsonArray upgrades=new JsonArray();
                for(Object value:(java.util.List<?>)menu.getClass().getField("upgradeSlots").get(menu)){
                    var slot=(net.minecraft.world.inventory.Slot)value;JsonObject upgrade=stack(slot.getItem());
                    upgrade.addProperty("containerSlot",slot.getContainerSlot());upgrade.addProperty("menuSlot",slot.index);upgrades.add(upgrade);
                }
                menuView.add("upgrades",upgrades);
            }catch(ReflectiveOperationException e){menuView.addProperty("upgradeReadError",e.toString());}
        }
        // Fixed, read-only adapter for properties synchronised by Iron Furnaces' normal menu.
        if(menu.getClass().getName().startsWith("ironfurnaces.container.furnaces.")){
            JsonObject machine=new JsonObject();
            for(String getter:List.of("getEnergy","getMaxEnergy","getTier","isAutoSplit","getAutoInput","getAutoOutput",
                "getIsFactory","getIsFurnace","getIsGenerator","getSettingTop","getSettingBottom","getSettingFront",
                "getSettingBack","getSettingLeft","getSettingRight","getRedstoneMode","isBurning")){
                try{Object value=menu.getClass().getMethod(getter).invoke(menu);
                    if(value instanceof Number number)machine.addProperty(getter,number);
                    else if(value instanceof Boolean bool)machine.addProperty(getter,bool);
                }catch(ReflectiveOperationException ignored){}
            }
            menuView.add("machine",machine);
        }
        if(menu.getClass().getName().equals("appeng.menu.implementations.InscriberMenu")){
            JsonObject machine=new JsonObject();
            for(String getter:List.of("getCurrentProgress","getMaxProgress","getSeparateSides","getAutoExport","getBufferSize"))try{
                Object value=menu.getClass().getMethod(getter).invoke(menu);
                if(value instanceof Number number)machine.addProperty(getter,number);
                else if(value instanceof Enum<?> enumeration)machine.addProperty(getter,enumeration.name());
            }catch(ReflectiveOperationException ignored){}
            menuView.add("machine",machine);
        }
        boolean mekanismTileMenu=false;
        for(Class<?> type=menu.getClass();type!=null;type=type.getSuperclass())
            if(type.getName().equals("mekanism.common.inventory.container.tile.MekanismTileContainer"))mekanismTileMenu=true;
        if(mekanismTileMenu){
            try{
                Object tile=menu.getClass().getMethod("getTileEntity").invoke(menu);JsonObject machine=Jsons.obj("class",tile.getClass().getName(),"energyUnits","Mekanism native synchronized values");
                for(String getter:List.of("getCurrentGeneration","getMaxOutput","getCurrentMultiplier","isBlacklistDimension","getActive","getDirection","isSorting","getLastUsage","getTicksRequired","getOperationsPerTick"))try{
                    Object value=tile.getClass().getMethod(getter).invoke(tile);
                    if(value instanceof Number number)machine.addProperty(getter,number);
                    else if(value instanceof Boolean bool)machine.addProperty(getter,bool);
                    else if(value instanceof Enum<?> enumeration)machine.addProperty(getter,enumeration.name());
                }catch(ReflectiveOperationException ignored){}
                try{
                    Object energy=tile.getClass().getMethod("getEnergyContainer").invoke(tile);
                    for(String getter:List.of("getEnergy","getMaxEnergy")){Object value=energy.getClass().getMethod(getter).invoke(energy);if(value instanceof Number number)machine.addProperty(getter,number);}
                }catch(ReflectiveOperationException ignored){}
                try{
                    Object tank=tile.getClass().getMethod("getChemicalTank").invoke(tile);
                    Class<?> tankType=Class.forName("mekanism.api.chemical.IChemicalTank"),stackType=Class.forName("mekanism.api.chemical.ChemicalStack");
                    Object chemical=tankType.getMethod("getStack").invoke(tank);
                    machine.add("chemical",Jsons.obj("id",stackType.getMethod("getTypeRegistryName").invoke(chemical).toString(),
                        "amount",tankType.getMethod("getStored").invoke(tank),"capacity",tankType.getMethod("getCapacity").invoke(tank)));
                }catch(ReflectiveOperationException ignored){}
                try{
                    Object component=tile.getClass().getMethod("getComponent").invoke(tile);
                    boolean syncActive=false;
                    if(mc.screen!=null)for(Object window:(java.util.Collection<?>)mc.screen.getClass().getMethod("getWindows").invoke(mc.screen))
                        if(window.getClass().getName().equals("mekanism.client.gui.element.window.GuiUpgradeWindow"))syncActive=true;
                    machine.addProperty("upgradesSynchronized",syncActive);
                    if(component!=null&&syncActive){
                        Class<?> upgradeType=Class.forName("mekanism.api.Upgrade"),componentType=Class.forName("mekanism.common.tile.component.TileComponentUpgrade");
                        JsonObject upgrades=new JsonObject();
                        for(String name:List.of("SPEED","ENERGY","CHEMICAL","MUFFLING","ANCHOR"))
                            upgrades.addProperty(name,((Number)componentType.getMethod("getUpgrades",upgradeType).invoke(component,upgradeType.getField(name).get(null))).intValue());
                        machine.add("upgrades",upgrades);
                    }
                    if(!syncActive)machine.addProperty("upgradeSyncNote","Upgrade counts omitted until the native upgrade window refreshes its component trackers");
                }catch(ReflectiveOperationException ignored){}
                menuView.add("machine",machine);
            }catch(ReflectiveOperationException e){menuView.addProperty("machineReadError",e.toString());}
        }
        result.add("menu",menuView);
        return result;
    }
    /** Loaded blocks only. No server files, hidden inventories or block entities are queried. */
    public static JsonArray scan(int radius) {
        var mc=Minecraft.getInstance(); JsonArray result=new JsonArray();
        if(mc.player==null||mc.level==null) return result;
        radius=Math.max(1,Math.min(radius,24)); BlockPos center=mc.player.blockPosition();
        for(BlockPos pos:BlockPos.betweenClosed(center.offset(-radius,-4,-radius),center.offset(radius,4,radius))) {
            if(!LoadedWorld.has(pos)) continue;
            var state=mc.level.getBlockState(pos);
            if(state.hasBlockEntity() || state.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)
                || state.is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) {
                result.add(Jsons.obj("x",pos.getX(),"y",pos.getY(),"z",pos.getZ(),
                    "id",BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),"state",state.toString()));
            }
        }
        return result;
    }
    public static JsonObject region(Map<String,String> query) {
        var mc=Actions.mc();int minX=Integer.parseInt(query.get("minX")),maxX=Integer.parseInt(query.get("maxX"));
        int minY=Integer.parseInt(query.get("minY")),maxY=Integer.parseInt(query.get("maxY"));
        int minZ=Integer.parseInt(query.get("minZ")),maxZ=Integer.parseInt(query.get("maxZ"));
        long volume=Math.multiplyExact(Math.multiplyExact((long)maxX-minX+1,(long)maxY-minY+1),(long)maxZ-minZ+1);
        if(minX>maxX||minY>maxY||minZ>maxZ||volume>32_768||volume<1)throw new IllegalArgumentException("Region must contain 1..32768 blocks");
        String filter=query.getOrDefault("blocks","");Set<String> ids=filter.isBlank()?Set.of():Set.of(filter.split(","));
        JsonArray blocks=new JsonArray();int unloaded=0;boolean truncated=false;
        for(BlockPos p:BlockPos.betweenClosed(minX,minY,minZ,maxX,maxY,maxZ)) {
            if(!LoadedWorld.has(p)){unloaded++;continue;}
            var state=mc.level.getBlockState(p);String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if((!ids.isEmpty()&&!ids.contains(id))||(ids.isEmpty()&&state.isAir()))continue;
            if(blocks.size()>=4096){truncated=true;continue;}
            blocks.add(Jsons.obj("x",p.getX(),"y",p.getY(),"z",p.getZ(),"id",id,"state",state.toString()));
        }
        return Jsons.obj("volume",volume,"unloaded",unloaded,"truncated",truncated,"blocks",blocks);
    }
    public static JsonArray entities(int radius) {
        var mc=Actions.mc();radius=Math.max(1,Math.min(64,radius));JsonArray result=new JsonArray();
        for(var entity:mc.level.getEntities(mc.player,mc.player.getBoundingBox().inflate(radius)).stream().limit(128).toList()) {
            JsonObject e=Jsons.obj("id",entity.getId(),"type",BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                "x",entity.getX(),"y",entity.getY(),"z",entity.getZ(),"alive",entity.isAlive());
            if(entity instanceof net.minecraft.world.entity.LivingEntity living)e.addProperty("health",living.getHealth());
            if(entity instanceof net.minecraft.world.entity.item.ItemEntity item)e.add("item",stack(item.getItem()));
            result.add(e);
        }
        return result;
    }
}
