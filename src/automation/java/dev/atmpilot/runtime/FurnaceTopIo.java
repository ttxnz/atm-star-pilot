package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.BlockPos;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Configures the observed own Iron Furnaces factory top through its visible button. */
public final class FurnaceTopIo implements PilotController.Job {
    private final BlockPos position;private final int mode;private Object menu;private int elapsed,wait,pending=-1,pendingTicks,clicks;private final Set<Integer> visited=new HashSet<>();
    public FurnaceTopIo(JsonObject d){position=Actions.pos(d);mode=Jsons.integer(d,"mode",3);if(mode!=1&&mode!=3)throw new IllegalArgumentException("Top mode must be native INPUT (1) or ALL (3)");}
    public String description(){return "Configure own factory furnace TOP mode "+mode+" at "+position.toShortString();}
    private static Object call(Object o,String n)throws Exception{return o.getClass().getMethod(n).invoke(o);}
    private static Object field(Object o,String n)throws Exception{for(Class<?> t=o.getClass();t!=null;t=t.getSuperclass())try{var f=t.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(n);}
    private static int origin(Object screen,String method,String fallback)throws Exception{try{return ((Number)call(screen,method)).intValue();}catch(NoSuchMethodException e){return ((Number)field(screen,fallback)).intValue();}}
    public boolean tick(PilotController c)throws Exception{
        var mc=Actions.mc();if(menu==null){menu=mc.player.containerMenu;if(!menu.getClass().getName().startsWith("ironfurnaces.container.furnaces.")||!mc.screen.getClass().getName().startsWith("ironfurnaces.gui.furnaces."))throw new IllegalStateException("Open the own factory furnace first");}
        ServerRules.checkScreenAction();ServerRules.checkBuild(position);Actions.checkReach(position);
        if(menu!=mc.player.containerMenu||!position.equals(call(menu,"getPos"))||!mc.player.containerMenu.getCarried().isEmpty()||!(Boolean)call(menu,"getIsFactory")||!(Boolean)call(menu,"getAutoOutput"))throw new IllegalStateException("Factory furnace menu/configuration changed");
        if(++elapsed>500)throw new IllegalStateException("Native top configuration timed out; inspect partial settings");if(wait-->0)return false;
        int current=((Number)call(menu,"getSettingTop")).intValue();
        if(current==mode){JsonArray cooking=new JsonArray();for(int n=0;n<((Number)call(menu,"getFactoryCooktimeSize")).intValue();n++)cooking.add((Number)menu.getClass().getMethod("getFactoryCookScaled",int.class,int.class).invoke(menu,n,100));Files.writeString(c.directory.resolve("last-furnace-top-io.json"),Jsons.GSON.toJson(Jsons.obj("observedAt",Instant.now().toString(),"state","completed_verified","x",position.getX(),"y",position.getY(),"z",position.getZ(),"top",current,"bottom",call(menu,"getSettingBottom"),"autoOutput",call(menu,"getAutoOutput"),"energy",call(menu,"getEnergy"),"maxEnergy",call(menu,"getMaxEnergy"),"redstoneMode",call(menu,"getRedstoneMode"),"burning",call(menu,"isBurning"),"factoryCookPercent",cooking,"nativeClicks",clicks)),StandardCharsets.UTF_8);return true;}
        if(pending>=0){if(current==pending){if(++pendingTicks>40)throw new IllegalStateException("Native furnace side click was not acknowledged; it was not repeated");return false;}pending=-1;pendingTicks=0;}
        if(((Number)call(mc.screen,"getShowConfig")).intValue()!=1){mc.screen.getClass().getMethod("setShowConfig",int.class).invoke(mc.screen,1);wait=6;return false;}
        if(!visited.add(current))throw new IllegalStateException("Native top mode cycle did not include input/output");
        Object button=mc.screen.getClass().getField("topButton").get(mc.screen);
        int x=((Number)field(button,"x")).intValue()+((Number)field(button,"width")).intValue()/2,y=((Number)field(button,"y")).intValue()+((Number)field(button,"height")).intValue()/2;
        if(!(Boolean)button.getClass().getMethod("hovering",double.class,double.class).invoke(button,(double)x,(double)y))throw new IllegalStateException("Native top button bounds changed");
        ScreenAccess.click(Jsons.obj("screenClass",mc.screen.getClass().getName(),"x",origin(mc.screen,"getGuiLeft","leftPos")+x,"y",origin(mc.screen,"getGuiTop","topPos")+y,"button",0));pending=current;clicks++;wait=8;return false;
    }
}
