package dev.atmpilot;

import com.google.gson.*;
import java.util.*;

/** Quantity comparison includes equipped curios, armor and soulbound items that never left the player. */
public final class RecoveryLedger {
    private RecoveryLedger(){}
    public static Map<String,Long> totals(JsonObject snapshot){
        Map<String,Long> counts=new TreeMap<>();
        for(String field:List.of("inventory","curios","craftingInputs")){
            if(!snapshot.has(field))continue;
            for(JsonElement entry:snapshot.getAsJsonArray(field)){
                JsonObject stack=entry.getAsJsonObject();String id=Jsons.str(stack,"id","minecraft:air");long count=Jsons.number(stack,"count",0);
                if(!id.equals("minecraft:air")&&count>0&&!id.equals("tombstone:grave_key"))counts.merge(id,count,Math::addExact);
            }
        }
        if(snapshot.has("carried")){
            JsonObject stack=snapshot.getAsJsonObject("carried");String id=Jsons.str(stack,"id","minecraft:air");long count=Jsons.number(stack,"count",0);
            if(!id.equals("minecraft:air")&&!id.equals("tombstone:grave_key")&&count>0)counts.merge(id,count,Math::addExact);
        }
        return counts;
    }
    public static JsonObject missing(JsonObject before,JsonObject after){
        Map<String,Long> actual=totals(after);JsonObject result=new JsonObject();
        for(var entry:totals(before).entrySet()){long shortfall=entry.getValue()-actual.getOrDefault(entry.getKey(),0L);if(shortfall>0)result.addProperty(entry.getKey(),shortfall);}
        List<JsonObject> restored=new ArrayList<>();for(String field:List.of("inventory","curios"))if(after.has(field))for(var value:after.getAsJsonArray(field))restored.add(value.getAsJsonObject());
        for(String field:List.of("inventory","curios"))if(before.has(field))for(var value:before.getAsJsonArray(field)){
            JsonObject stack=value.getAsJsonObject();String id=Jsons.str(stack,"id","");if(result.has(id)||!stack.has("material"))continue;
            String components=Jsons.str(stack.getAsJsonObject("material"),"components","");if(components.isEmpty())continue;
            JsonObject data=JsonParser.parseString(components).getAsJsonObject();if(!data.has("sophisticatedcore:storage_uuid"))continue;
            JsonElement uuid=data.get("sophisticatedcore:storage_uuid");boolean found=false;
            for(JsonObject candidate:restored){
                if(!id.equals(Jsons.str(candidate,"id",""))||!candidate.has("material"))continue;
                String other=Jsons.str(candidate.getAsJsonObject("material"),"components","");
                if(!other.isEmpty()&&uuid.equals(JsonParser.parseString(other).getAsJsonObject().get("sophisticatedcore:storage_uuid"))){found=true;break;}
            }
            if(!found)result.addProperty(id+" [original storage UUID]",1);
        }
        return result;
    }
}
