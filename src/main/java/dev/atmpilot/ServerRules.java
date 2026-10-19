package dev.atmpilot;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.PlayerMainInvWrapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** User's shared-server boundaries. Missing/invalid rules fail closed for world/container mutations. */
public final class ServerRules {
    private ServerRules(){}
    public static JsonObject snapshot(){
        Path path=PilotMod.CONTROLLER.directory.resolve("server-rules.json");
        try{
            return RulesCache.read(path);
        }catch(Exception e){throw new IllegalStateException("Read AGENTS.md and install valid server-rules.json before modifying the world",e);}
    }
    private static boolean contains(JsonObject box,BlockPos pos){
        return pos.getX()>=Jsons.integer(box,"minX",Integer.MAX_VALUE)&&pos.getX()<=Jsons.integer(box,"maxX",Integer.MIN_VALUE)
            &&pos.getZ()>=Jsons.integer(box,"minZ",Integer.MAX_VALUE)&&pos.getZ()<=Jsons.integer(box,"maxZ",Integer.MIN_VALUE)
            &&pos.getY()>=Jsons.integer(box,"minY",-4096)&&pos.getY()<=Jsons.integer(box,"maxY",4096);
    }
    private static boolean ownArea(JsonObject rules,BlockPos pos){
        if(!rules.has("buildAreas"))return false;
        String dimension=Actions.mc().level.dimension().location().toString();
        for(var value:rules.getAsJsonArray("buildAreas")){
            JsonObject area=value.getAsJsonObject();
            if(Jsons.str(area,"dimension","").equals(dimension)&&Jsons.bool(area,"surveyed",false)
                &&Jsons.bool(area,"claimsChecked",false)&&contains(area,pos))return true;
        }
        return false;
    }
    private static boolean sharedBase(JsonObject rules,BlockPos pos){
        JsonObject base=rules.getAsJsonObject("sharedBase");
        return base!=null&&Jsons.str(base,"dimension","").equals(Actions.mc().level.dimension().location().toString())
            &&base.has("conservativeBounds")&&contains(base.getAsJsonObject("conservativeBounds"),pos);
    }
    public static void checkBuild(BlockPos pos){
        if(!ownArea(snapshot(),pos))throw new IllegalStateException("Server rule: construction/mining/configuration is allowed only inside the surveyed independent production area; shared base is protected");
    }
    public static void checkOpen(BlockPos pos){
        if(TransportRules.allows(pos))return;
        if(OwnGraves.isOwned(pos)&&snapshot().has("deathRecovery")&&Jsons.bool(snapshot().getAsJsonObject("deathRecovery"),"enabled",false))return;
        var mc=Actions.mc();JsonObject rules=snapshot();if(ownArea(rules,pos))return;
        String id=BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString();
        boolean storage=id.contains("chest")||id.contains("barrel")||id.equals("ae2:cable_bus");
        if(sharedBase(rules,pos)&&Jsons.bool(rules,"allowSharedStorageWithdraw",false)&&storage){
            if(!mc.player.getMainHandItem().isEmpty()||!mc.player.getOffhandItem().isEmpty()||mc.player.isShiftKeyDown())
                throw new IllegalStateException("Open shared storage with empty hands and no sneaking to avoid changing its blocks or configuration");
            return;
        }
        throw new IllegalStateException("Server rule: shared machines and infrastructure may not be operated or changed; only authorised AE/chest storage access is allowed");
    }
    private static boolean personalMenu(){
        var menu=Actions.mc().player.containerMenu;
        return menu.containerId==0||menu.getClass().getName().startsWith("net.p3pp3rf1y.sophisticatedbackpacks.");
    }
    private static boolean playerSlot(Slot slot){
        var inventory=Actions.mc().player.getInventory();
        if(slot.container==inventory)return true;
        if(slot instanceof SlotItemHandler wrapped){
            var handler=wrapped.getItemHandler();
            return handler instanceof InvWrapper inv&&inv.getInv()==inventory
                ||handler instanceof PlayerMainInvWrapper mainInv&&mainInv.getInventoryPlayer()==inventory;
        }
        return false;
    }
    public static void checkMenuClick(int index){
        var mc=Actions.mc();JsonObject rules=snapshot();if(personalMenu()||ownArea(rules,mc.player.blockPosition()))return;
        var menu=mc.player.containerMenu;String type=menu.getClass().getName();
        if((type.equals("appeng.menu.me.common.MEStorageMenu")||type.equals("appeng.menu.me.items.CraftingTermMenu"))
            &&sharedBase(rules,mc.player.blockPosition())&&Jsons.bool(rules,"allowSharedStorageWithdraw",false)
            &&playerSlot(menu.getSlot(index)))return;
        boolean storage=type.equals("net.minecraft.world.inventory.ChestMenu")||type.equals("net.minecraft.world.inventory.ShulkerBoxMenu")
            ||type.startsWith("net.p3pp3rf1y.sophisticatedstorage.");
        if(!sharedBase(rules,mc.player.blockPosition())||!Jsons.bool(rules,"allowSharedStorageWithdraw",false)||!storage)
            throw new IllegalStateException("Server rule: this shared container is not an authorised storage inventory");
        Slot slot=menu.getSlot(index);String slotType=slot.getClass().getName();
        if(playerSlot(slot)||slotType.equals("net.minecraft.world.inventory.Slot")
            ||slotType.equals("net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase$1"))return;
        throw new IllegalStateException("Server rule: shared container upgrades, filters and configuration slots are protected");
    }
    public static void checkScreenAction(){
        var mc=Actions.mc();if(personalMenu()||ownArea(snapshot(),mc.player.blockPosition()))return;
        if(mc.player.containerMenu.containerId==0)return;
        throw new IllegalStateException("Server rule: shared-machine GUI controls are protected; use observed storage item-slot transfers for authorised withdrawals");
    }
    public static void checkItemUse(){
        var mc=Actions.mc();String animation=mc.player.getMainHandItem().getUseAnimation().name();
        if(animation.equals("EAT")||animation.equals("DRINK"))return;
        checkBuild(mc.player.blockPosition());
    }
}
