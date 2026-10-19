package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Copies one observed physical reagent into the own export bus's native ghost filter and returns the cursor. */
public final class AeReagentFilter implements PilotController.Job {
    private final BlockPos position;private final PlanModel.Material material;private final String face;private AbstractContainerMenu menu;private Object host;private Slot config,source;private int phase,wait,elapsed,count;private long before;
    public AeReagentFilter(JsonObject d){position=Actions.pos(d);face=Jsons.str(d,"face","").toLowerCase(Locale.ROOT);String item=Jsons.str(d,"item","");if(!Set.of("mekanism:enriched_redstone","mekanism:enriched_carbon","mekanism:enriched_diamond","mekanism:enriched_refined_obsidian","alltheores:osmium_ingot","minecraft:basalt").contains(item))throw new IllegalArgumentException("An observed Mek enrichment or combining reagent is required");material=new PlanModel.Material(item,"");}
    public String description(){return "Configure own AE reagent export filter "+material.id()+" at "+position.toShortString();}
    private static Object call(Object o,String n)throws Exception{return o.getClass().getMethod(n).invoke(o);}
    private static int origin(Object screen,String method,String fallback)throws Exception{try{return ((Number)call(screen,method)).intValue();}catch(NoSuchMethodException e){for(Class<?> t=screen.getClass();t!=null;t=t.getSuperclass())try{var f=t.getDeclaredField(fallback);f.setAccessible(true);return f.getInt(screen);}catch(NoSuchFieldException ignored){}throw e;}}
    private void report(String state)throws Exception{JsonObject j=Jsons.obj("observedAt",Instant.now().toString(),"state",state,"x",position.getX(),"y",position.getY(),"z",position.getZ(),"item",material.id(),"phase",phase,"menuId",Actions.mc().player.containerMenu.containerId,"cursor",WorldView.stack(Actions.mc().player.containerMenu.getCarried()),"filter",config==null?null:WorldView.stack(config.getItem()),"inventoryBefore",before,"inventoryNow",WorldView.count(material));Files.writeString(PilotMod.CONTROLLER.directory.resolve("last-ae-reagent-filter.json"),Jsons.GSON.toJson(j),StandardCharsets.UTF_8);if(state.equals("completed_verified"))Files.writeString(PilotMod.CONTROLLER.directory.resolve("ae-reagent-filter-"+position.getX()+"-"+position.getY()+"-"+position.getZ()+".json"),Jsons.GSON.toJson(j),StandardCharsets.UTF_8);}
    public boolean tick(PilotController c)throws Exception{try{return step();}catch(Exception e){try{report("failed_needs_replan");}catch(Exception ignored){}throw e;}}
    private boolean step()throws Exception{
        var mc=Actions.mc();if(menu==null){
            menu=mc.player.containerMenu;if(!menu.getClass().getName().equals("appeng.menu.implementations.IOBusMenu")||!menu.getCarried().isEmpty())throw new IllegalStateException("Open an own AE export bus with an empty cursor");
            host=call(menu,"getHost");if(!host.getClass().getName().equals("appeng.parts.automation.ExportBusPart")||!((BlockEntity)call(host,"getBlockEntity")).getBlockPos().equals(position))throw new IllegalStateException("Export bus host differs from the observed target");
            if(!face.isEmpty()&&!((net.minecraft.core.Direction)call(host,"getSide")).getName().equals(face))throw new IllegalStateException("Export bus face differs from the observed reagent side");
            Object semantic=Class.forName("appeng.menu.SlotSemantics").getField("CONFIG").get(null);List<?> list=(List<?>)menu.getClass().getMethod("getSlots",Class.forName("appeng.menu.SlotSemantic")).invoke(menu,semantic);
            if(list.isEmpty())throw new IllegalStateException("Native export filter slots absent");config=(Slot)list.getFirst();
            for(Object o:list){Slot s=(Slot)o;if(!s.getItem().isEmpty()&&(s!=config||!WorldView.material(s.getItem()).equals(material)))throw new IllegalStateException("Export bus already has another filter; no overwrite was attempted");}
            before=WorldView.count(material);for(Slot s:menu.slots)if(s.container==mc.player.getInventory()&&!s.getItem().isEmpty()&&WorldView.material(s.getItem()).equals(material)){source=s;count=s.getItem().getCount();break;}
            if(source==null)throw new IllegalStateException("Carry an actual reagent before copying its filter");
        }
        ServerRules.checkScreenAction();ServerRules.checkBuild(position);Actions.checkReach(position);
        if(menu!=mc.player.containerMenu||mc.screen==null||call(menu,"getHost")!=host)throw new IllegalStateException("Export bus menu changed; retain the cursor checkpoint");
        if(++elapsed>400)throw new IllegalStateException("Native ghost filter timed out; no click was repeated");if(wait-->0)return false;
        if(phase==0){if(!menu.getCarried().isEmpty())throw new IllegalStateException("Unexpected cursor");if(WorldView.material(config.getItem()).equals(material)){report("completed_verified");return true;}Actions.click(menu.containerId,source.index,0,ClickType.PICKUP);phase=1;wait=6;return false;}
        if(phase==1){if(!WorldView.material(menu.getCarried()).equals(material)||menu.getCarried().getCount()!=count||!source.getItem().isEmpty())throw new IllegalStateException("Observed reagent pickup differs from request");ScreenAccess.click(Jsons.obj("screenClass",mc.screen.getClass().getName(),"x",origin(mc.screen,"getGuiLeft","leftPos")+config.x+8,"y",origin(mc.screen,"getGuiTop","topPos")+config.y+8,"button",0));phase=2;wait=8;return false;}
        if(phase==2){if(!WorldView.material(config.getItem()).equals(material)||!WorldView.material(menu.getCarried()).equals(material)||menu.getCarried().getCount()!=count)throw new IllegalStateException("Native ghost copy was not confirmed; cursor retained");Actions.click(menu.containerId,source.index,0,ClickType.PICKUP);phase=3;wait=8;return false;}
        if(phase==3){if(!menu.getCarried().isEmpty()||WorldView.count(material)!=before||!WorldView.material(source.getItem()).equals(material)||source.getItem().getCount()!=count||!WorldView.material(config.getItem()).equals(material))throw new IllegalStateException("Physical reagent return or ghost filter differs from request");report("completed_verified");return true;}
        throw new IllegalStateException("Unexpected export filter phase");
    }
}
