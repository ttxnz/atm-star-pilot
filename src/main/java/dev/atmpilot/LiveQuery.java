package dev.atmpilot;

import com.google.gson.JsonObject;
import java.util.function.Supplier;

/** Named supplier keeps the existing HTTP class's lambda method schema stable for hot replacement. */
public final class LiveQuery implements Supplier<JsonObject> {
    private final PilotController controller;
    private final int radius;
    public LiveQuery(PilotController controller,int radius){this.controller=controller;this.radius=radius;}
    public JsonObject get(){return LiveTelemetry.read(controller,radius);}
}
