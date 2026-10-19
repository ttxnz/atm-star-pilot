package dev.atmpilot;

import com.google.gson.*;
import java.util.*;

public final class Jsons {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private Jsons() {}
    public static JsonObject obj(Object... fields) {
        JsonObject result = new JsonObject();
        for (int i=0;i<fields.length;i+=2) result.add(fields[i].toString(), GSON.toJsonTree(fields[i+1]));
        return result;
    }
    public static String str(JsonObject o,String key,String fallback) {
        return o.has(key) ? o.get(key).getAsString() : fallback;
    }
    public static int integer(JsonObject o,String key,int fallback) {
        return o.has(key) ? o.get(key).getAsBigDecimal().intValueExact() : fallback;
    }
    public static long number(JsonObject o,String key,long fallback) {
        return o.has(key) ? o.get(key).getAsBigDecimal().longValueExact() : fallback;
    }
    public static double decimal(JsonObject o,String key,double fallback) {
        double value=o.has(key) ? o.get(key).getAsDouble() : fallback;
        if(!Double.isFinite(value))throw new IllegalArgumentException("Non-finite number: "+key);
        return value;
    }
    public static boolean bool(JsonObject o,String key,boolean fallback) {
        return o.has(key) ? o.get(key).getAsBoolean() : fallback;
    }
    public static JsonElement sorted(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            value.getAsJsonObject().keySet().stream().sorted().forEach(k -> result.add(k,sorted(value.getAsJsonObject().get(k))));
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray(); value.getAsJsonArray().forEach(v -> result.add(sorted(v))); return result;
        }
        return value;
    }
    public static JsonObject plan(PlanModel.Plan plan) {
        List<PlanModel.Amount> missing = plan.missing().entrySet().stream()
            .map(e -> new PlanModel.Amount(e.getKey(),e.getValue())).toList();
        return obj("target",plan.target(),"count",plan.count(),"ready",plan.ready(),
                   "steps",plan.steps(),"missing",missing,"issues",plan.issues());
    }
}
