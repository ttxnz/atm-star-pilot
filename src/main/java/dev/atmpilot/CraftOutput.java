package dev.atmpilot;

import com.google.gson.*;

/** Some native Mek recipes preserve input energy. All other output components remain exact. */
public final class CraftOutput {
    private CraftOutput(){}
    public static boolean matches(PlanModel.Material expected,PlanModel.Material actual){
        if(expected.equals(actual))return true;
        if(CraftOwnership.candidate(expected,actual))return CraftOwnership.matches(expected,actual,Actions.mc().player.getUUID());
        if(!expected.id().equals(actual.id())||!(expected.id().startsWith("mekanism:")||expected.id().startsWith("mekanismgenerators:")))return false;
        try{
            JsonObject wanted=expected.components().isEmpty()?new JsonObject():JsonParser.parseString(expected.components()).getAsJsonObject();
            JsonObject got=actual.components().isEmpty()?new JsonObject():JsonParser.parseString(actual.components()).getAsJsonObject();
            if(wanted.has("mekanism:energy")||!got.has("mekanism:energy"))return false;
            JsonObject energy=got.getAsJsonObject("mekanism:energy");
            if(energy.size()!=1||!energy.has("energy_containers")||!energy.get("energy_containers").isJsonArray())return false;
            JsonArray values=energy.getAsJsonArray("energy_containers");if(values.isEmpty())return false;
            for(JsonElement value:values){
                if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||value.getAsBigDecimal().signum()<0)return false;
                value.getAsBigDecimal().toBigIntegerExact();
            }
            got.remove("mekanism:energy");return wanted.equals(got);
        }catch(RuntimeException e){return false;}
    }
    public static long count(PlanModel.Material expected){
        long result=0;for(var entry:WorldView.inventory().entrySet())if(matches(expected,entry.getKey()))result=Math.addExact(result,entry.getValue());return result;
    }
}
