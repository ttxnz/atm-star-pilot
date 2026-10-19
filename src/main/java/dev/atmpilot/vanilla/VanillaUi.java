package dev.atmpilot.vanilla;

import com.google.gson.JsonObject;
import dev.atmpilot.Jsons;
import dev.atmpilot.ScreenAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import java.util.Set;
import java.util.function.Supplier;

/** Normal out-of-world menu input, limited to creating/selecting the user's test world. */
public final class VanillaUi implements Supplier<JsonObject> {
    private final JsonObject data;
    public VanillaUi(JsonObject data){this.data=data.deepCopy();}
    public JsonObject get(){
        var m=Minecraft.getInstance();if(m.player!=null||m.level!=null)throw new IllegalStateException("World setup UI is only available outside a world");
        if(m.screen==null)throw new IllegalStateException("No setup screen");String type=m.screen.getClass().getName();
        if(!Set.of("net.minecraft.client.gui.screens.TitleScreen","net.minecraft.client.gui.screens.worldselection.SelectWorldScreen","net.minecraft.client.gui.screens.worldselection.CreateWorldScreen","net.minecraft.client.gui.screens.ConfirmScreen").contains(type))throw new IllegalStateException("Unsupported world setup screen: "+type);
        if(!type.equals(Jsons.str(data,"screenClass","")))throw new IllegalStateException("Setup screen changed; observe again");
        String action=Jsons.str(data,"action","click");
        if(action.equals("text")){
            if(!(m.screen.getFocused() instanceof EditBox edit))throw new IllegalStateException("Focus an observed world-name text box first");
            String text=Jsons.str(data,"text","");if(text.length()>80||text.contains("\n")||text.contains("\r"))throw new IllegalArgumentException("One short world name required");edit.setValue(text);
        }else if(action.equals("key")){
            int key=Jsons.integer(data,"key",-1);if(!Set.of(256,257,258,262,263,264,265).contains(key))throw new IllegalArgumentException("Unsupported setup navigation key");m.screen.keyPressed(key,0,0);
        }else if(action.equals("click")){
            double x=Jsons.decimal(data,"x",-1),y=Jsons.decimal(data,"y",-1);
            if(!Double.isFinite(x)||!Double.isFinite(y)||x<0||y<0||x>=m.getWindow().getGuiScaledWidth()||y>=m.getWindow().getGuiScaledHeight())throw new IllegalArgumentException("Setup click out of bounds");
            m.screen.mouseClicked(x,y,0);if(m.screen!=null)m.screen.mouseReleased(x,y,0);
        }else throw new IllegalArgumentException("Unsupported setup action");
        return ScreenAccess.describe();
    }
}
