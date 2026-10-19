package dev.atmpilot;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CraftOwnershipTest {
    private final UUID owner=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final PlanModel.Material expected=PlanModel.Material.item("modularrouters:security_upgrade");
    private PlanModel.Material actual(String json){return new PlanModel.Material(expected.id(),json);}
    private final String stamped="{\"modularrouters:security_owner\":{\"id\":[0,0,0,1],\"name\":\"player\",\"properties\":[]}}";
    @Test void nativeCraftedOwnerIsRecognized(){assertTrue(CraftOwnership.matches(expected,actual(stamped),owner));}
    @Test void anotherPlayersUpgradeIsRejected(){assertFalse(CraftOwnership.matches(expected,actual(stamped),UUID.randomUUID()));}
    @Test void additionalComponentsAndExplicitRequirementsStayExact(){
        assertFalse(CraftOwnership.matches(expected,actual(stamped.substring(0,stamped.length()-1)+",\"minecraft:custom_name\":\"changed\"}"),owner));
        assertFalse(CraftOwnership.matches(actual(stamped),actual(stamped),owner));
    }
    @Test void InvalidUuidAndOtherItemsAreRejected(){
        assertFalse(CraftOwnership.matches(expected,actual(stamped.replace("[0,0,0,1]","[0.5,0,0,1]")),owner));
        assertFalse(CraftOwnership.matches(PlanModel.Material.item("allthetweaks:atm_star"),actual(stamped),owner));
    }
}
