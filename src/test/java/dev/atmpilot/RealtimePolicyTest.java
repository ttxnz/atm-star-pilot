package dev.atmpilot;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RealtimePolicyTest {
    @Test void explicitAutonomousModeIgnoresOrdinaryInputButKeepsEmergencyStop(){
        assertFalse(ManualInputPolicy.keyYields(true,87));
        assertFalse(ManualInputPolicy.keyYields(true,342));
        assertTrue(ManualInputPolicy.keyYields(true,297));
        assertTrue(ManualInputPolicy.keyYields(true,296));
        assertTrue(ManualInputPolicy.keyYields(false,87));
        assertFalse(ManualInputPolicy.autonomous(new com.google.gson.JsonObject()));
        var rules=com.google.gson.JsonParser.parseString("{\"manualControlPolicy\":{\"mode\":\"autonomous_until_explicit_stop\"}}").getAsJsonObject();
        assertTrue(ManualInputPolicy.autonomous(rules));
    }
    @Test void manualHoldCannotBeClearedByOrdinaryOrSpoofedJobs() {
        assertThrows(IllegalStateException.class,()->ManualControlGate.require(true,"Resume the recorded recovery"));
        assertThrows(IllegalStateException.class,()->ManualControlGate.require(true,(ManualResumeJob)()->false));
        assertDoesNotThrow(()->ManualControlGate.require(true,(ManualResumeJob)()->true));
        assertDoesNotThrow(()->ManualControlGate.require(false,new Object()));
    }
    @Test void hoverPreviewEndsAtTargetInsteadOfProjectingThroughTheFloor() {
        assertEquals(-4.84,MotionPolicy.preview(-4.84,-.8,-2),.00001);
        assertEquals(-.15,MotionPolicy.preview(-.15,-.2,-.15),.00001);
        assertEquals(.1,MotionPolicy.preview(.1,.4,.1),.00001);
        assertEquals(-.8,MotionPolicy.preview(3,-.2,0),.00001);
        assertThrows(IllegalArgumentException.class,()->MotionPolicy.preview(Double.NaN,0,0));
    }
    @Test void movingTargetReplansLocallyWithoutAnExternalRoundTrip() {
        assertFalse(MotionPolicy.replan(103,100,100,false));
        assertTrue(MotionPolicy.replan(104,100,1,false));
        assertFalse(MotionPolicy.replan(104,100,.1,false));
        assertTrue(MotionPolicy.replan(104,100,.1,true));
        assertTrue(MotionPolicy.replan(10,-1,0,false));
    }
    @Test void bystandersWallsAndCooldownPreventAttacks() {
        assertTrue(CombatPolicy.canHit(true,true,false,1,20));
        assertFalse(CombatPolicy.canHit(false,true,false,1,20));
        assertFalse(CombatPolicy.canHit(true,false,false,1,20));
        assertFalse(CombatPolicy.canHit(true,true,true,1,20));
        assertFalse(CombatPolicy.canHit(true,true,false,.8,20));
        assertFalse(CombatPolicy.canHit(true,true,false,1,1));
    }
    @Test void passiveNeutralNamedAndBossEntitiesAreExcluded() {
        for(String id:new String[]{"minecraft:chicken","minecraft:player","minecraft:wolf","minecraft:enderman","minecraft:zombified_piglin","minecraft:wither","example:unknown_monster"})
            assertFalse(CombatPolicy.permitted(id,false,false,false),id);
        assertTrue(CombatPolicy.permitted("minecraft:zombie",false,false,false));
        assertTrue(CombatPolicy.permitted("minecraft:creeper",false,false,false));
        assertFalse(CombatPolicy.permitted("minecraft:creeper",true,false,false));
        assertFalse(CombatPolicy.permitted("minecraft:zombie",true,false,false));
        assertFalse(CombatPolicy.permitted("minecraft:zombie",false,true,false));
        assertFalse(CombatPolicy.permitted("minecraft:zombie",false,false,true));
    }
    @Test void dangerousFightsRequestExistingEscapeInsteadOfContinuing() {
        assertTrue(CombatPolicy.shouldEscape(1,1,false,1));
        assertTrue(CombatPolicy.shouldEscape(.5,1,true,4));
        assertFalse(CombatPolicy.shouldEscape(1.01,1,true,4));
        assertFalse(CombatPolicy.shouldEscape(24,1,false,1));
    }
}
