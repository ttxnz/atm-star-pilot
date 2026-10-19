package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Configures only the visible native buttons of an observed own AE inscriber. */
public final class AeInscriberConfigure implements PilotController.Job {
    private final BlockPos position;private final int requiredSpeedCards;
    private Object menu,host;private String pendingGetter,pendingValue;private int elapsed,wait,pendingTicks,clicks;
    private final Set<String> visited=new HashSet<>();
    public AeInscriberConfigure(JsonObject d){position=Actions.pos(d);requiredSpeedCards=Jsons.integer(d,"requiredSpeedCards",4);if(requiredSpeedCards<0||requiredSpeedCards>4)throw new IllegalArgumentException("Inscriber speed card count must be 0..4");}
    public String description(){return "Configure own inscriber combined input, 64 buffer and native AE auto-export at "+position.toShortString();}
    private static Object call(Object o,String name)throws Exception{return o.getClass().getMethod(name).invoke(o);}
    private static Object field(Object o,String name)throws Exception{for(Class<?> t=o.getClass();t!=null;t=t.getSuperclass())try{var f=t.getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    private String value(String getter)throws Exception{return call(menu,getter).toString();}
    private void report(String state)throws Exception{JsonObject j=Jsons.obj("observedAt",Instant.now().toString(),"state",state,"x",position.getX(),"y",position.getY(),"z",position.getZ(),"menuId",Actions.mc().player.containerMenu.containerId,"clicks",clicks,"requiredSpeedCards",requiredSpeedCards,"separateSides",menu==null?null:value("getSeparateSides"),"autoExport",menu==null?null:value("getAutoExport"),"buffer",menu==null?null:value("getBufferSize"));Files.writeString(PilotMod.CONTROLLER.directory.resolve("last-ae-inscriber-configuration.json"),Jsons.GSON.toJson(j),StandardCharsets.UTF_8);if(state.equals("completed_verified"))Files.writeString(PilotMod.CONTROLLER.directory.resolve("ae-inscriber-configuration-"+position.getX()+"-"+position.getY()+"-"+position.getZ()+".json"),Jsons.GSON.toJson(j),StandardCharsets.UTF_8);}
    public boolean tick(PilotController c)throws Exception{try{return step();}catch(Exception e){try{report("failed_needs_replan");}catch(Exception ignored){}throw e;}}
    private boolean step()throws Exception{
        var mc=Actions.mc();if(menu==null){menu=mc.player.containerMenu;if(!menu.getClass().getName().equals("appeng.menu.implementations.InscriberMenu")||mc.screen==null||!mc.screen.getClass().getName().equals("appeng.client.gui.implementations.InscriberScreen"))throw new IllegalStateException("Open the observed own native inscriber first");host=call(menu,"getHost");if(!(host instanceof BlockEntity block)||!block.getBlockPos().equals(position)||block.getLevel()!=mc.level)throw new IllegalStateException("Inscriber host differs from target");}
        ServerRules.checkScreenAction();ServerRules.checkBuild(position);Actions.checkReach(position);
        if(menu!=mc.player.containerMenu||mc.screen==null||call(menu,"getHost")!=host||!mc.player.containerMenu.getCarried().isEmpty())throw new IllegalStateException("Inscriber menu or cursor changed; no click repeated");
        if(++elapsed>500)throw new IllegalStateException("Inscriber native configuration timed out");if(wait-->0)return false;
        if(pendingGetter!=null){if(value(pendingGetter).equals(pendingValue)){if(++pendingTicks>40)throw new IllegalStateException("Native setting click was not acknowledged; no click repeated");return false;}pendingGetter=null;pendingTicks=0;}
        for(String[] setting:new String[][]{{"getSeparateSides","NO","separateSidesBtn"},{"getAutoExport","YES","autoExportBtn"},{"getBufferSize","SIXTY_FOUR","bufferSizeBtn"}}){
            String current=value(setting[0]);if(current.equals(setting[1]))continue;
            Object button=field(mc.screen,setting[2]);if(!call(button,"getCurrentValue").toString().equals(current)){wait=4;return false;}
            if(!visited.add(setting[0]+":"+current))throw new IllegalStateException("Native setting cycle missed requested value");
            int x=((Number)call(button,"getX")).intValue()+((Number)call(button,"getWidth")).intValue()/2,y=((Number)call(button,"getY")).intValue()+((Number)call(button,"getHeight")).intValue()/2;
            ScreenAccess.click(Jsons.obj("screenClass",mc.screen.getClass().getName(),"x",x,"y",y,"button",0));pendingGetter=setting[0];pendingValue=current;clicks++;wait=8;return false;
        }
        int speed=0;for(var slot:mc.player.containerMenu.slots)if(slot.container!=mc.player.getInventory()&&WorldView.material(slot.getItem()).id().equals("ae2:speed_card"))speed+=slot.getItem().getCount();
        if(speed!=requiredSpeedCards)throw new IllegalStateException("Observed inscriber speed cards differ from requirement");report("completed_verified");return true;
    }
    public void cancel(){try{report("interrupted_needs_replan");}catch(Exception ignored){}}
}
