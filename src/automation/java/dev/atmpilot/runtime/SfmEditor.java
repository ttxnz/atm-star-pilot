package dev.atmpilot.runtime;

import com.google.gson.JsonObject;
import dev.atmpilot.*;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.core.BlockPos;

/** Edits only the visible native SFM text field; its Done button performs the normal save. */
public final class SfmEditor implements PilotController.Job {
    public static final class Inspect implements PilotController.Job {
        public String description(){return "Read the visible own SFM runtime log text";}
        public boolean tick(PilotController c)throws Exception{
            var mc=Actions.mc();ServerRules.checkScreenAction();
            if(mc.screen==null||!mc.screen.getClass().getName().equals("ca.teamdman.sfm.client.screen.LogsScreen")||!mc.player.containerMenu.getClass().getName().equals("ca.teamdman.sfm.common.containermenu.ManagerContainerMenu"))throw new IllegalStateException("Open the own SFM log screen first");
            BlockPos p=(BlockPos)mc.player.containerMenu.getClass().getField("MANAGER_POSITION").get(mc.player.containerMenu);ServerRules.checkBuild(p);Actions.checkReach(p);
            String text=null;for(var child:mc.screen.children())if(child instanceof MultiLineEditBox box){if(text!=null)throw new IllegalStateException("Ambiguous SFM log widgets");text=box.getValue();}
            if(text==null)throw new IllegalStateException("Native SFM log field is absent");
            java.nio.file.Files.writeString(c.directory.resolve("last-sfm-visible-logs.json"),Jsons.GSON.toJson(Jsons.obj("observedAt",java.time.Instant.now().toString(),"x",p.getX(),"y",p.getY(),"z",p.getZ(),"logs",text)),java.nio.charset.StandardCharsets.UTF_8);return true;
        }
    }
    private final String expected,text;
    public SfmEditor(JsonObject data){expected=Jsons.str(data,"expected","");text=Jsons.str(data,"text","");if(text.isEmpty()||text.length()>16384)throw new IllegalArgumentException("One bounded SFM program is required");}
    public String description(){return "Replace the observed own SFM editor text through its native text widget";}
    public boolean tick(PilotController controller)throws Exception {
        var mc=Actions.mc();ServerRules.checkScreenAction();
        if(mc.screen==null||!mc.screen.getClass().getName().equals("ca.teamdman.sfm.client.screen.text_editor.SFMTextEditScreenV1")||!mc.player.containerMenu.getClass().getName().equals("ca.teamdman.sfm.common.containermenu.ManagerContainerMenu")||!mc.player.containerMenu.getCarried().isEmpty())throw new IllegalStateException("Own SFM editor/menu or cursor changed");
        BlockPos position=(BlockPos)mc.player.containerMenu.getClass().getField("MANAGER_POSITION").get(mc.player.containerMenu);ServerRules.checkBuild(position);Actions.checkReach(position);
        MultiLineEditBox editor=null;for(var child:mc.screen.children())if(child instanceof MultiLineEditBox box){if(editor!=null)throw new IllegalStateException("Ambiguous SFM text fields");editor=box;}
        if(editor==null||!editor.getValue().equals(expected))throw new IllegalStateException("SFM text changed since observation; no edit applied");
        editor.setValue(text);if(!editor.getValue().equals(text))throw new IllegalStateException("Native SFM text field did not retain the complete program");return true;
    }
}
