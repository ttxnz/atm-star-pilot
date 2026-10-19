package dev.atmpilot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class RulesCacheTest {
    @TempDir Path directory;
    @Test void changedDeletedAndMalformedRulesNeverFallBackToCachedAuthorization()throws Exception {
        var file=directory.resolve("rules.json");Files.writeString(file,"{\"version\":1,\"allow\":true}");
        var first=RulesCache.read(file);first.addProperty("allow",false);
        assertTrue(RulesCache.read(file).get("allow").getAsBoolean());
        Files.writeString(file,"{\"version\":1,\"allow\":false}");
        assertFalse(RulesCache.read(file).get("allow").getAsBoolean());
        Files.writeString(file,"{malformed");
        assertThrows(Exception.class,()->RulesCache.read(file));
        Files.delete(file);assertThrows(Exception.class,()->RulesCache.read(file));
    }
}
