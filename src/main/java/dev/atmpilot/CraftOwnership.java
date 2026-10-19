package dev.atmpilot;

import com.google.gson.*;
import java.util.UUID;

/** The security upgrade is stamped by its native crafting hook; no other components are relaxed. */
public final class CraftOwnership {
    private CraftOwnership(){}
    public static boolean candidate(PlanModel.Material expected,PlanModel.Material actual){return expected.id().equals("modularrouters:security_upgrade")&&actual.id().equals(expected.id())&&expected.components().isEmpty();}
    public static boolean matches(PlanModel.Material expected,PlanModel.Material actual,UUID owner){
        if(!candidate(expected,actual))return false;
        try{
            var root=JsonParser.parseString(actual.components()).getAsJsonObject();
            if(root.size()!=1||!root.has("modularrouters:security_owner"))return false;
            var profile=root.getAsJsonObject("modularrouters:security_owner");
            for(String key:profile.keySet())if(!key.equals("id")&&!key.equals("name")&&!key.equals("properties"))return false;
            var id=profile.getAsJsonArray("id");if(id.size()!=4)return false;int[] bits=new int[4];for(int i=0;i<4;i++)bits[i]=id.get(i).getAsBigDecimal().intValueExact();
            var actualOwner=new UUID(((long)bits[0]<<32)|(bits[1]&0xffffffffL),((long)bits[2]<<32)|(bits[3]&0xffffffffL));
            return actualOwner.equals(owner);
        }catch(RuntimeException e){return false;}
    }
}
