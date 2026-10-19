package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Own-machine side changes through the visible native Mek buttons, one acknowledged click at a time. */
public final class MekConfigure implements PilotController.Job {
    private final BlockPos position;
    private final String transmission;
    private final LinkedHashMap<String,String> desired=new LinkedHashMap<>();
    private final Boolean ejecting;
    private Object menu,tile,window;
    private int elapsed,wait,clicks,pendingTicks;
    private boolean opened,reported;
    private String pendingSide,pendingValue;
    private final Map<String,Set<String>> visited=new HashMap<>();
    public MekConfigure(JsonObject data){
        position=Actions.pos(data);transmission=Jsons.str(data,"transmission","ITEM").toUpperCase(Locale.ROOT);
        if(!Set.of("ITEM","FLUID","CHEMICAL","ENERGY").contains(transmission))throw new IllegalArgumentException("Unsupported transmission");
        if(data.has("sides"))for(var e:data.getAsJsonObject("sides").entrySet()){
            String side=e.getKey().toUpperCase(Locale.ROOT),value=e.getValue().getAsString().toUpperCase(Locale.ROOT);
            if(!Set.of("FRONT","BACK","LEFT","RIGHT","TOP","BOTTOM").contains(side)||!Set.of("NONE","INPUT","INPUT_1","INPUT_2","OUTPUT","OUTPUT_1","OUTPUT_2","INPUT_OUTPUT","ENERGY","EXTRA").contains(value))throw new IllegalArgumentException("Unsupported side configuration");
            desired.put(side,value);
        }
        ejecting=data.has("ejecting")?data.get("ejecting").getAsBoolean():null;
        if(desired.isEmpty()&&ejecting==null)throw new IllegalArgumentException("A concrete side or ejection setting is required");
    }
    public String description(){return "Configure own Mek "+transmission+" sides at "+position.toShortString();}
    private static Object call(Object o,String n)throws Exception{return o.getClass().getMethod(n).invoke(o);}
    private static Object field(Object o,String n)throws Exception{
        for(Class<?> t=o.getClass();t!=null;t=t.getSuperclass())try{var f=t.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(n);
    }
    private Object info()throws Exception{
        Object config=call(tile,"getConfig");Class<?> type=Class.forName("mekanism.common.lib.transmitter.TransmissionType");
        return config.getClass().getMethod("getConfig",type).invoke(config,type.getField(transmission).get(null));
    }
    private Map<String,String> sides(Object info)throws Exception{
        Map<String,String> result=new LinkedHashMap<>();for(Object v:(Set<?>)call(info,"getSideConfig")){var e=(Map.Entry<?,?>)v;result.put(e.getKey().toString(),e.getValue().toString());}return result;
    }
    private void guard()throws Exception{
        var mc=Actions.mc();ServerRules.checkScreenAction();ServerRules.checkBuild(position);Actions.checkReach(position);
        if(menu!=mc.player.containerMenu||mc.screen==null||!mc.player.containerMenu.getCarried().isEmpty()||call(menu,"getTileEntity")!=tile||!((BlockEntity)tile).getBlockPos().equals(position))throw new IllegalStateException("Observed Mek menu, position or cursor changed");
    }
    private void click(Object widget)throws Exception{
        if(!(widget instanceof AbstractWidget w)||!w.active||!w.visible)throw new IllegalStateException("The requested native Mek button is not visible and active");
        ScreenAccess.click(Jsons.obj("screenClass",Actions.mc().screen.getClass().getName(),"x",w.getX()+w.getWidth()/2,"y",w.getY()+w.getHeight()/2,"button",0));clicks++;wait=8;
    }
    private void report(String state)throws Exception{
        var actual=info();JsonObject j=Jsons.obj("observedAt",Instant.now().toString(),"state",state,"x",position.getX(),"y",position.getY(),"z",position.getZ(),"transmission",transmission,"desired",desired,"actual",sides(actual),"ejecting",call(actual,"isEjecting"),"nativeClicks",clicks,"menuId",Actions.mc().player.containerMenu.containerId);
        Files.writeString(PilotMod.CONTROLLER.directory.resolve("last-mek-configuration.json"),Jsons.GSON.toJson(j),StandardCharsets.UTF_8);
        if(state.equals("completed_verified"))Files.writeString(PilotMod.CONTROLLER.directory.resolve("mek-config-"+position.getX()+"-"+position.getY()+"-"+position.getZ()+"-"+transmission+".json"),Jsons.GSON.toJson(j),StandardCharsets.UTF_8);
    }
    public boolean tick(PilotController c)throws Exception{
        try{return step();}catch(Exception e){if(tile!=null&&!reported)try{report("failed_needs_replan");reported=true;}catch(Exception ignored){}throw e;}
    }
    private boolean step()throws Exception{
        var mc=Actions.mc();if(menu==null){menu=mc.player.containerMenu;tile=call(menu,"getTileEntity");
            if(!(tile instanceof BlockEntity b)||!b.getBlockPos().equals(position))throw new IllegalStateException("Open the exact own Mek machine first");
            String name=tile.getClass().getName();if(!name.startsWith("mekanism.common.tile.factory.")&&!name.equals("mekanism.common.tile.TileEntityQuantumEntangloporter")&&!Set.of("mekanism.common.tile.machine.TileEntityElectrolyticSeparator","mekanism.common.tile.machine.TileEntityPressurizedReactionChamber","mekanism.common.tile.machine.TileEntityRotaryCondensentrator").contains(name))throw new IllegalStateException("Unsupported own machine for native side configuration");
            if(info()==null)throw new IllegalStateException("Machine does not support the requested transmission");
        }
        guard();if(++elapsed>900)throw new IllegalStateException("Mek side configuration timed out; inspect the acknowledged partial configuration");if(wait-->0)return false;
        if(window==null){
            for(Object w:(Iterable<?>)call(mc.screen,"getWindows"))if(w.getClass().getName().equals("mekanism.client.gui.element.window.GuiSideConfiguration")){window=w;break;}
            if(window==null){
                if(opened)throw new IllegalStateException("Native side configuration window did not open");
                for(var v:ScreenAccess.describe().getAsJsonArray("widgets")){var w=v.getAsJsonObject();if(Jsons.str(w,"class","").equals("mekanism.client.gui.element.tab.window.GuiSideConfigurationTab")&&Jsons.bool(w,"active",false)&&Jsons.bool(w,"visible",false)){
                    ScreenAccess.click(Jsons.obj("screenClass",mc.screen.getClass().getName(),"x",Jsons.integer(w,"x",0)+Jsons.integer(w,"width",0)/2,"y",Jsons.integer(w,"y",0)+Jsons.integer(w,"height",0)/2,"button",0));opened=true;clicks++;wait=8;return false;
                }}throw new IllegalStateException("Visible native side configuration tab was not found");
            }
        }
        if(!field(window,"currentType").toString().equals(transmission)){
            for(Object tab:(List<?>)field(window,"configTabs"))if(call(tab,"getTransmissionType").toString().equals(transmission)){click(tab);return false;}
            throw new IllegalStateException("Visible native transmission tab was not found");
        }
        Object config=info();Map<String,String> actual=sides(config);
        if(pendingSide!=null){
            String now=pendingSide.equals("EJECT")?call(config,"isEjecting").toString():actual.get(pendingSide);
            if(Objects.equals(now,pendingValue)){if(++pendingTicks>40)throw new IllegalStateException("Native side change was not acknowledged; no click was repeated");return false;}
            pendingSide=null;pendingValue=null;pendingTicks=0;
        }
        var buttons=(Map<?,?>)field(window,"sideConfigButtons");
        for(var e:desired.entrySet())if(!e.getValue().equals(actual.get(e.getKey()))){
            String old=actual.get(e.getKey());if(old==null||!visited.computeIfAbsent(e.getKey(),k->new HashSet<>()).add(old))throw new IllegalStateException("Requested side mode is absent from the native button cycle");
            for(var button:buttons.entrySet())if(button.getKey().toString().equals(e.getKey())){
                if(!call(button.getValue(),"getDataType").toString().equals(old))throw new IllegalStateException("Native side button and synchronized machine disagree");
                pendingSide=e.getKey();pendingValue=old;click(button.getValue());return false;
            }
            throw new IllegalStateException("Native side button was not found");
        }
        if(ejecting!=null&&!ejecting.equals(call(config,"isEjecting"))){pendingSide="EJECT";pendingValue=call(config,"isEjecting").toString();click(field(window,"ejectButton"));return false;}
        report("completed_verified");reported=true;return true;
    }
}
