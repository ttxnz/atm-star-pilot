package dev.atmpilot;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryTest {
    @Test void manualStopsAndRepeatedDeathsDoNotRestartAutomaticRecovery(){
        for(String state:new String[]{"interrupted_by_player","manual_hold_no_death","needs_attention","waiting_respawn"})assertTrue(RecoveryControl.holdAfterNewDeath(true,state,1,2));
        assertTrue(RecoveryControl.holdAfterNewDeath(false,"recovered_needs_replan",3,2));
        assertFalse(RecoveryControl.holdAfterNewDeath(true,"recovered_needs_replan",2,2));
        assertFalse(RecoveryControl.holdAfterNewDeath(false,"watching",1,2));
    }
    private JsonObject parse(String value){return JsonParser.parseString(value).getAsJsonObject();}
    private JsonObject rules(){return parse("{\"survivalCommands\":{\"allow\":[\"home\",\"back\"]}}");}
    @Test void authorizedOrdinaryTravelCommandsAreAccepted(){
        assertEquals("back",SurvivalCommands.validate("/back",rules()));
        assertEquals("home factory",SurvivalCommands.validate("home factory",rules()));
    }
    @Test void commandInjectionAndOperatorCommandsAreRejected(){
        for(String input:new String[]{"tp 0 100 0","give @s minecraft:diamond","back\nop test","home a;op test","home ../other","back extra"})
            assertThrows(IllegalArgumentException.class,()->SurvivalCommands.validate(input,rules()));
    }
    @Test void unapprovedTravelCommandIsRejected(){
        assertThrows(IllegalStateException.class,()->SurvivalCommands.validate("home",parse("{\"survivalCommands\":{\"allow\":[\"back\"]}}")));
    }
    @Test void soulboundItemsAndRestoredCuriosCountTogether(){
        var before=parse("{\"inventory\":[{\"id\":\"minecraft:diamond_sword\",\"count\":1},{\"id\":\"minecraft:iron_ingot\",\"count\":64}],\"curios\":[{\"id\":\"sophisticatedbackpacks:diamond_backpack\",\"count\":1}]}");
        var after=parse("{\"inventory\":[{\"id\":\"minecraft:diamond_sword\",\"count\":1},{\"id\":\"minecraft:iron_ingot\",\"count\":32},{\"id\":\"minecraft:iron_ingot\",\"count\":32},{\"id\":\"tombstone:grave_key\",\"count\":1},{\"id\":\"sophisticatedbackpacks:diamond_backpack\",\"count\":1}]}");
        assertTrue(RecoveryLedger.missing(before,after).isEmpty());
    }
    @Test void partialRecoveryReportsMissingQuantity(){
        var before=parse("{\"inventory\":[{\"id\":\"minecraft:diamond\",\"count\":64}],\"curios\":[{\"id\":\"sophisticatedbackpacks:diamond_backpack\",\"count\":1}]}");
        var after=parse("{\"inventory\":[{\"id\":\"minecraft:diamond\",\"count\":63}]}");
        var missing=RecoveryLedger.missing(before,after);assertEquals(1,missing.get("minecraft:diamond").getAsInt());assertEquals(1,missing.get("sophisticatedbackpacks:diamond_backpack").getAsInt());
    }
    @Test void aDifferentBackpackCannotProveTheOriginalContentsWereRecovered(){
        var before=parse("{\"curios\":[{\"id\":\"sophisticatedbackpacks:diamond_backpack\",\"count\":1,\"material\":{\"components\":\"{\\\"sophisticatedcore:storage_uuid\\\":[1,2,3,4]}\"}}]}");
        var after=parse("{\"curios\":[{\"id\":\"sophisticatedbackpacks:diamond_backpack\",\"count\":1,\"material\":{\"components\":\"{\\\"sophisticatedcore:storage_uuid\\\":[5,6,7,8]}\"}}]}");
        assertFalse(RecoveryLedger.missing(before,after).isEmpty());assertTrue(RecoveryLedger.missing(before,before).isEmpty());
    }
    @Test void itemsOnTheCursorAndInTheCraftingGridMustAlsoBeRecovered(){
        var before=parse("{\"inventory\":[{\"id\":\"minecraft:iron_ingot\",\"count\":20}],\"craftingInputs\":[{\"id\":\"minecraft:iron_ingot\",\"count\":4}],\"carried\":{\"id\":\"minecraft:iron_ingot\",\"count\":40}}");
        var after=parse("{\"inventory\":[{\"id\":\"minecraft:iron_ingot\",\"count\":64}]}");
        assertTrue(RecoveryLedger.missing(before,after).isEmpty());assertEquals(64,RecoveryLedger.totals(before).get("minecraft:iron_ingot"));
    }
}
