package dev.atmpilot;

import com.google.gson.*;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.*;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.Screenshot;
import java.nio.file.Path;
import java.util.*;

public final class ScreenAccess {
    private ScreenAccess() {}
    public static JsonObject describe(){
        var mc=net.minecraft.client.Minecraft.getInstance();JsonArray widgets=new JsonArray();
        if(mc.screen!=null)visit(mc.screen,widgets,Collections.newSetFromMap(new IdentityHashMap<>()),0);
        JsonObject view=Jsons.obj("class",mc.screen==null?null:mc.screen.getClass().getName(),
            "width",mc.getWindow().getGuiScaledWidth(),"height",mc.getWindow().getGuiScaledHeight(),"widgets",widgets);
        if(mc.screen!=null&&mc.screen.getClass().getName().startsWith("ironfurnaces.gui.furnaces.")){
            JsonArray custom=new JsonArray();
            for(var field:mc.screen.getClass().getFields()){
                if(!field.getType().getName().equals("ironfurnaces.util.gui.FurnaceGuiButton"))continue;
                try{
                    Object button=field.get(mc.screen);if(button==null)continue;var type=button.getClass();
                    int x=type.getField("left").getInt(button)+type.getField("x").getInt(button);
                    int y=type.getField("top").getInt(button)+type.getField("y").getInt(button);
                    custom.add(Jsons.obj("name",field.getName(),"x",x,"y",y,"width",type.getField("width").getInt(button),
                        "height",type.getField("height").getInt(button),"visibilityUnknown",true));
                }catch(ReflectiveOperationException ignored){}
            }
            view.add("customWidgets",custom);
            try{view.addProperty("showConfig",(Number)mc.screen.getClass().getMethod("getShowConfig").invoke(mc.screen));}
            catch(ReflectiveOperationException ignored){}
        }
        return view;
    }
    private static void visit(GuiEventListener listener,JsonArray widgets,Set<Object> seen,int depth){
        if(depth>10||widgets.size()>500||!seen.add(listener))return;
        if(listener instanceof AbstractWidget widget)widgets.add(Jsons.obj("x",widget.getX(),"y",widget.getY(),
            "width",widget.getWidth(),"height",widget.getHeight(),"text",widget.getMessage().getString(),
            "active",widget.active,"visible",widget.visible,"class",widget.getClass().getName()));
        if(listener instanceof ContainerEventHandler container)container.children().forEach(child->visit(child,widgets,seen,depth+1));
    }
    public static void click(JsonObject data){
        var mc=Actions.mc();if(mc.screen==null)throw new IllegalStateException("No screen is open");
        checkScreen(data);double x=Jsons.decimal(data,"x",-1),y=Jsons.decimal(data,"y",-1);int button=Jsons.integer(data,"button",0);
        if(x<0||y<0||x>=mc.getWindow().getGuiScaledWidth()||y>=mc.getWindow().getGuiScaledHeight()||button<0||button>2)
            throw new IllegalArgumentException("Screen coordinates/button outside bounds");
        mc.screen.mouseClicked(x,y,button);if(mc.screen!=null)mc.screen.mouseReleased(x,y,button);
    }
    public static void key(JsonObject data){
        var mc=Actions.mc();if(mc.screen==null)throw new IllegalStateException("No screen is open");checkScreen(data);
        int key=Jsons.integer(data,"key",-1);int modifiers=Jsons.integer(data,"modifiers",0);
        if(key<0||key>348||modifiers<0||modifiers>15)throw new IllegalArgumentException("Invalid GLFW key/modifiers");
        mc.screen.keyPressed(key,0,modifiers);if(mc.screen!=null)mc.screen.keyReleased(key,0,modifiers);
    }
    public static void text(String text){
        var mc=Actions.mc();if(mc.screen==null||mc.screen instanceof ChatScreen)throw new IllegalStateException("Text requires a non-chat game screen");
        ServerRules.checkScreenAction();
        if(text.length()>16_384||text.indexOf('\n')>=0||text.indexOf('\r')>=0)throw new IllegalArgumentException("Text must be one bounded line; send Enter as a separate action");
        for(char ch:text.toCharArray())mc.screen.charTyped(ch,0);
    }
    private static void checkScreen(JsonObject data){
        ServerRules.checkScreenAction();
        String expected=Jsons.str(data,"screenClass","");
        if(!expected.isEmpty()&&!Actions.mc().screen.getClass().getName().equals(expected))throw new IllegalStateException("Screen changed; observe before clicking");
    }
    public static void screenshot(Path file)throws Exception {
        var mc=net.minecraft.client.Minecraft.getInstance();try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(file);}
    }
}
