package dev.atmpilot;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

@Mod(value="atmpilot",dist=Dist.CLIENT)
public final class PilotMod {
    public static final Logger LOG=LogUtils.getLogger();
    public static final PilotController CONTROLLER=new PilotController();
    public static final KeyMapping OPEN=new KeyMapping("key.atmpilot.open",InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_F7,"key.categories.atmpilot");
    public static final KeyMapping STOP=new KeyMapping("key.atmpilot.stop",InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_F8,"key.categories.atmpilot");
    private PilotHttp transport;
    public PilotMod(IEventBus bus){
        bus.addListener(this::setup);bus.addListener(this::keys);
        NeoForge.EVENT_BUS.addListener(CONTROLLER::preTick);NeoForge.EVENT_BUS.addListener(CONTROLLER::postTick);
        NeoForge.EVENT_BUS.addListener(this::manualKey);NeoForge.EVENT_BUS.addListener(this::manualMouse);
    }
    private void keys(RegisterKeyMappingsEvent event){event.register(OPEN);event.register(STOP);}
    private void setup(FMLClientSetupEvent event){event.enqueueWork(()->{
        try{transport=new PilotHttp(CONTROLLER);}catch(Exception e){LOG.error("Cannot start ATM Pilot loopback transport",e);}
        // Undo precisely the temporary bindings used in the earlier desktop-control experiment.
        var mc=Minecraft.getInstance();var o=mc.options;
        if(o.keyUp.getKey().getType()==InputConstants.Type.MOUSE&&o.keyUp.getKey().getValue()==0&&o.keyAttack.getKey().equals(InputConstants.UNKNOWN)){
            o.keyUp.setKey(InputConstants.getKey(GLFW.GLFW_KEY_W,0));o.keyAttack.setKey(InputConstants.Type.MOUSE.getOrCreate(0));
            o.rawMouseInput().set(true);KeyMapping.resetMapping();o.save();LOG.info("Restored temporary forward/attack mouse bindings");
        }
    });}
    private void manualKey(InputEvent.Key event){
        if(dev.atmpilot.vanilla.VanillaRun.running()&&event.getAction()==GLFW.GLFW_PRESS){dev.atmpilot.vanilla.VanillaRun.stop("Physical keyboard input; explicit resume required");return;}
        if(!ManualInputPolicy.physicalKeyYields(event.getKey()))return;
        if(event.getAction()==GLFW.GLFW_PRESS)DeathRecovery.manualInput();
        if(CONTROLLER.running()&&event.getAction()==GLFW.GLFW_PRESS)CONTROLLER.stop("Manual keyboard input; control returned to player");
    }
    private void manualMouse(InputEvent.MouseButton.Pre event){
        if(dev.atmpilot.vanilla.VanillaRun.running()&&event.getAction()==GLFW.GLFW_PRESS){dev.atmpilot.vanilla.VanillaRun.stop("Physical mouse input; explicit resume required");return;}
        if(!ManualInputPolicy.physicalMouseYields())return;
        if(event.getAction()==GLFW.GLFW_PRESS)DeathRecovery.manualInput();
        if(CONTROLLER.running()&&event.getAction()==GLFW.GLFW_PRESS)CONTROLLER.stop("Manual mouse input; control returned to player");
    }
}
