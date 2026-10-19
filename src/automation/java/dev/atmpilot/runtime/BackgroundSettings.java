package dev.atmpilot.runtime;

import com.google.gson.JsonObject;
import dev.atmpilot.Actions;
import dev.atmpilot.Jsons;
import dev.atmpilot.PilotController;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Local vanilla background preference only; never changes emergency-stop or player abilities. */
public final class BackgroundSettings implements PilotController.Job {
    private final boolean pause;
    public BackgroundSettings(JsonObject data) { pause = Jsons.bool(data, "pauseOnLostFocus", false); }
    public String description() { return "Set the local vanilla pause-on-lost-focus preference"; }
    public boolean tick(PilotController controller) throws Exception {
        var options = Actions.mc().options;
        boolean before = options.pauseOnLostFocus;
        options.pauseOnLostFocus = pause;
        options.save();
        Files.writeString(controller.directory.resolve("last-background-settings.json"),
                Jsons.GSON.toJson(Jsons.obj("before", before, "pauseOnLostFocus", options.pauseOnLostFocus)),
                StandardCharsets.UTF_8);
        return true;
    }
}
