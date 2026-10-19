package dev.atmpilot;

import com.google.gson.JsonObject;

/** User can explicitly keep autonomous control across incidental physical input; emergency stop remains. */
public final class ManualInputPolicy {
    private ManualInputPolicy(){}
    public static boolean autonomous(JsonObject rules){
        var policy=rules.getAsJsonObject("manualControlPolicy");
        return policy!=null&&Jsons.str(policy,"mode","").equals("autonomous_until_explicit_stop");
    }
    public static boolean keyYields(boolean autonomous,int key){return !autonomous||key==297||key==296;}
    public static boolean physicalKeyYields(int key){try{return keyYields(autonomous(ServerRules.snapshot()),key);}catch(IllegalStateException missingRules){return true;}}
    public static boolean physicalMouseYields(){try{return !autonomous(ServerRules.snapshot());}catch(IllegalStateException missingRules){return true;}}
}
