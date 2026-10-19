package dev.atmpilot;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CraftOutputTest {
    private PlanModel.Material item(String id,String components){return new PlanModel.Material(id,components);}
    @Test void nativeMekResultCanPreserveCharge(){
        assertTrue(CraftOutput.matches(item("mekanismgenerators:wind_generator",""),item("mekanismgenerators:wind_generator","{\"mekanism:energy\":{\"energy_containers\":[200000]}}")));
    }
    @Test void explicitChargeRequirementStaysExact(){
        assertFalse(CraftOutput.matches(item("mekanism:energy_tablet","{\"mekanism:energy\":{\"energy_containers\":[1000000]}}"),item("mekanism:energy_tablet","")));
    }
    @Test void namesOwnershipAndOtherComponentsAreNotDiscarded(){
        assertFalse(CraftOutput.matches(item("mekanismgenerators:wind_generator",""),item("mekanismgenerators:wind_generator","{\"minecraft:custom_name\":\"other\",\"mekanism:energy\":{\"energy_containers\":[200000]}}")));
    }
    @Test void starComponentsAlwaysStayExact(){
        assertFalse(CraftOutput.matches(item("allthemodium:unobtainium_vibranium_alloy_block",""),item("allthemodium:unobtainium_vibranium_alloy_block","{\"minecraft:enchantments\":{\"levels\":{\"minecraft:unbreaking\":1}}}")));
    }
    @Test void energyShapeAndQuantityMustBeValid(){
        assertFalse(CraftOutput.matches(item("mekanism:energy_tablet",""),item("mekanism:energy_tablet","{\"mekanism:energy\":{\"energy_containers\":[-1]}}")));
        assertFalse(CraftOutput.matches(item("mekanism:energy_tablet",""),item("mekanism:energy_tablet","{\"mekanism:energy\":{\"energy_containers\":[0.5]}}")));
    }
}
