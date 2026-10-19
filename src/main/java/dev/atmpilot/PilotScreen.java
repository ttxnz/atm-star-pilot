package dev.atmpilot;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.concurrent.CompletableFuture;

public final class PilotScreen extends Screen {
    private final PilotController controller;private String planText="Live JEI recipes are indexed after entering a world.";
    public PilotScreen(PilotController controller){super(Component.literal("ATM Star Pilot"));this.controller=controller;}
    @Override protected void init(){
        addRenderableWidget(Button.builder(Component.literal("Analyze ATM Star"),button->{
            try{
                var input=controller.planningInput();planText="Planning...";
                CompletableFuture.supplyAsync(()->PilotController.plan(input,"allthetweaks:atm_star",1,""))
                    .whenComplete((plan,error)->minecraft.execute(()->{
                        if(error!=null)planText=error.getMessage();
                        else{controller.savePlan(plan);planText="Steps: "+plan.getAsJsonArray("steps").size()+", shortages: "+plan.getAsJsonArray("missing").size()+". Saved: config/atmpilot/last-plan.json";}
                    }));
            }catch(Exception e){planText=e.getMessage();}
        }).bounds(width/2-150,height-100,300,20).build());
        addRenderableWidget(Button.builder(Component.literal("Stop / release controls"),button->controller.stop("Stopped in control panel"))
            .bounds(width/2-150,height-75,300,20).build());
        addRenderableWidget(Button.builder(Component.literal("Back to game"),button->onClose()).bounds(width/2-150,height-50,300,20).build());
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){
        graphics.fill(0,0,width,height,0xE018222A);
        graphics.drawCenteredString(font,title,width/2,24,0xFFFFFF);
        graphics.drawString(font,"F7: Control panel   F8: Emergency stop",24,55,0x9BC5DD);
        graphics.drawWordWrap(font,Component.literal(controller.status().toString()),24,80,width-48,0xFFFFFF);
        graphics.drawWordWrap(font,Component.literal(planText==null?"":planText),24,150,width-48,0xA8E4A0);
        super.render(graphics,mouseX,mouseY,partialTick);
    }
    @Override public boolean isPauseScreen(){return false;}
}
