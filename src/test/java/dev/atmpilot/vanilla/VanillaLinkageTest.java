package dev.atmpilot.vanilla;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VanillaLinkageTest {
    @Test void coreEntryPointsLinkWithoutLoadingJeiInterfaces()throws Exception{
        // compileOnly JEI is intentionally absent from the test runtime.
        assertThrows(ClassNotFoundException.class,()->Class.forName("mezz.jei.api.IModPlugin"));
        for(String name:new String[]{"dev.atmpilot.PilotMod","dev.atmpilot.PilotController","dev.atmpilot.PilotHttp","dev.atmpilot.WorldView","dev.atmpilot.OptionalJei","dev.atmpilot.vanilla.VanillaRun"}){
            var type=Class.forName(name,false,getClass().getClassLoader());
            assertDoesNotThrow(type::getDeclaredMethods,name);
        }
    }
}
