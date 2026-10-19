package dev.atmpilot;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurvivalReflexTest {
    @Test void manualControlAndRecoveryBlockAutonomousMovement(){
        assertTrue(SurvivalPolicy.eligible(true,true,false,true,false));
        assertFalse(SurvivalPolicy.eligible(true,true,true,true,false));
        assertFalse(SurvivalPolicy.eligible(true,true,false,true,true));
        assertFalse(SurvivalPolicy.eligible(true,false,false,true,false));
        assertFalse(SurvivalPolicy.eligible(true,true,false,false,false));
    }
    @Test void manualStopReasonsNeverTriggerRescue(){
        for(String reason:new String[]{"Manual keyboard input; control returned to player","F8 emergency stop","Stopped by controller","Control panel opened"})assertTrue(SurvivalPolicy.manualReason(reason));
        assertFalse(SurvivalPolicy.manualReason("IllegalStateException: Block interaction is obstructed"));
    }
}
