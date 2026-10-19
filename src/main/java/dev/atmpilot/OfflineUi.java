package dev.atmpilot;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import java.util.Set;
import java.util.function.Supplier;

/** Normal menu input for recovering a disconnected session; never starts world jobs. */
public final class OfflineUi implements Supplier<JsonObject> {
    private final JsonObject data;
    public OfflineUi(JsonObject data){this.data=data.deepCopy();}
    public JsonObject get(){
        var mc=Minecraft.getInstance();
        if(mc.level!=null||mc.player!=null)throw new IllegalStateException("Connection UI is only available outside a world");
        if(Jsons.bool(DeathRecovery.status(),"manualHold",false))throw new IllegalStateException("Explicit emergency stop still applies");
        if(mc.screen==null)throw new IllegalStateException("No connection screen is open");
        String actual=mc.screen.getClass().getName();
        if(!Set.of("net.minecraft.client.gui.screens.DisconnectedScreen","net.minecraft.client.gui.screens.TitleScreen",
                "net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen").contains(actual))
            throw new IllegalStateException("Observe the current connection screen before proceeding: "+actual);
        if(!actual.equals(Jsons.str(data,"screenClass","")))throw new IllegalStateException("Connection screen changed; observe again");
        if(Jsons.str(data,"action","click").equals("key")){
            int key=Jsons.integer(data,"key",-1);
            if(!Set.of(257,256,258,262,263,264,265).contains(key))throw new IllegalArgumentException("Only connection menu navigation keys are supported");
            mc.screen.keyPressed(key,0,0);if(mc.screen!=null)mc.screen.keyReleased(key,0,0);
        }else{
            double x=Jsons.decimal(data,"x",-1),y=Jsons.decimal(data,"y",-1);
            if(!Double.isFinite(x)||!Double.isFinite(y)||x<0||y<0||x>=mc.getWindow().getGuiScaledWidth()||y>=mc.getWindow().getGuiScaledHeight())
                throw new IllegalArgumentException("Connection menu coordinates are outside bounds");
            mc.screen.mouseClicked(x,y,0);if(mc.screen!=null)mc.screen.mouseReleased(x,y,0);
        }
        return ScreenAccess.describe();
    }
}
