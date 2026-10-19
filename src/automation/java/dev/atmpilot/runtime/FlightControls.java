package dev.atmpilot.runtime;

import dev.atmpilot.Actions;
import java.lang.reflect.Field;
import net.minecraft.world.entity.player.Player;

/** Translates the steering policy into normal client keys without writing abilities or motion. */
final class FlightControls {
    private static final Field JUMP_TIMER=jumpTimer();
    private int tick,lastRise=-100;
    private static Field jumpTimer(){
        try{var field=Player.class.getDeclaredField("jumpTriggerTime");field.setAccessible(true);return field;}
        catch(ReflectiveOperationException|RuntimeException ignored){return null;}
    }
    void vertical(double target){
        var mc=Actions.mc();var player=mc.player;tick++;
        boolean held=player.input.jumping,allowRise=held;
        if(!held&&tick-lastRise>=8){
            try{allowRise=JUMP_TIMER!=null?JUMP_TIMER.getInt(player)==0:tick>8;}
            catch(IllegalAccessException ignored){allowRise=tick>8;}
        }
        int key=FlightSteering.vertical(target-player.getY(),player.getDeltaMovement().y,player.getAbilities().getFlyingSpeed(),allowRise);
        if(key>0&&!held)lastRise=tick;
        mc.options.keyJump.setDown(key>0);mc.options.keyShift.setDown(key<0);
    }
    static void horizontal(FlightSteering.Controls keys){
        var mc=Actions.mc();var player=mc.player;
        float pitch=mc.level.dimension().equals(net.minecraft.world.level.Level.END)&&player.getY()<16?80:8;
        float nextPitch=player.getXRot()+Math.max(-6,Math.min(6,pitch-player.getXRot()));
        Actions.look(keys.yaw(),nextPitch);
        mc.options.keyUp.setDown(keys.forward()>0);mc.options.keyDown.setDown(keys.forward()<0);
        mc.options.keyLeft.setDown(keys.sideways()>0);mc.options.keyRight.setDown(keys.sideways()<0);
        mc.options.keySprint.setDown(keys.sprint());
    }
}
