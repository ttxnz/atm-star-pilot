package dev.atmpilot;

import com.google.gson.*;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.stream.Stream;

/** Optional FTB Quests adapter. Reads client-synchronised data and opens the ordinary quest UI. */
public final class QuestAccess {
    private QuestAccess() {}
    private static Object call(Object object,String method)throws ReflectiveOperationException{return object.getClass().getMethod(method).invoke(object);}
    private static String title(Object object)throws ReflectiveOperationException{return ((Component)call(object,"getTitle")).getString();}
    public static JsonObject snapshot(String filter,int offset,int limit){
        try{
            Class<?> fileClass=Class.forName("dev.ftb.mods.ftbquests.client.ClientQuestFile");
            Object file=fileClass.getField("INSTANCE").get(null);
            if(file==null)return Jsons.obj("available",false,"reason","Quest data not synchronised");
            Object team=fileClass.getField("selfTeamData").get(file);
            if(team==null)return Jsons.obj("available",false,"reason","Team data not synchronised");
            Class<?> objectClass=Class.forName("dev.ftb.mods.ftbquests.quest.QuestObject");
            Class<?> taskClass=Class.forName("dev.ftb.mods.ftbquests.quest.task.Task");
            List<JsonObject> quests=new ArrayList<>();
            for(Object chapter:(Collection<?>)call(file,"getAllChapters")){
                for(Object quest:(Collection<?>)call(chapter,"getQuests")){
                    String name=title(quest);if(!(name+" "+title(chapter)).toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)))continue;
                    long id=((Number)call(quest,"getId")).longValue();
                    JsonArray tasks=new JsonArray();
                    for(Object task:(Collection<?>)call(quest,"getTasks")){
                        JsonObject detail=Jsons.obj("id",String.format("%016X",((Number)call(task,"getId")).longValue()),
                            "title",title(task),"type",task.getClass().getSimpleName(),"progress",team.getClass().getMethod("getProgress",taskClass).invoke(team,task),
                            "max",call(task,"getMaxProgress"));
                        if(task.getClass().getSimpleName().equals("ItemTask")){
                            JsonArray items=new JsonArray();
                            for(Object item:(Collection<?>)call(task,"getValidDisplayItems"))items.add(WorldView.stack((net.minecraft.world.item.ItemStack)item));
                            detail.add("items",items);detail.addProperty("consumesResources",(Boolean)call(task,"consumesResources"));
                        }
                        tasks.add(detail);
                    }
                    List<String> dependencies=new ArrayList<>();
                    try(Stream<?> stream=(Stream<?>)call(quest,"streamDependencies")){
                        for(Object dep:stream.toList())dependencies.add(String.format("%016X",((Number)call(dep,"getId")).longValue()));
                    }
                    quests.add(Jsons.obj("id",String.format("%016X",id),"title",name,"chapter",title(chapter),
                        "complete",team.getClass().getMethod("isCompleted",objectClass).invoke(team,quest),
                        "progress",team.getClass().getMethod("getRelativeProgress",objectClass).invoke(team,quest),
                        "tasks",tasks,"dependencies",dependencies));
                }
            }
            return Jsons.obj("available",true,"total",quests.size(),"quests",quests.stream().skip(Math.max(0,offset)).limit(Math.min(100,Math.max(1,limit))).toList());
        }catch(ClassNotFoundException e){return Jsons.obj("available",false,"reason","FTB Quests is not installed");}
        catch(ReflectiveOperationException|LinkageError e){return Jsons.obj("available",false,"reason",e.toString());}
    }
    public static void open(String quest)throws Exception {
        Class<?> fileClass=Class.forName("dev.ftb.mods.ftbquests.client.ClientQuestFile");
        if(quest.isEmpty())fileClass.getMethod("openGui").invoke(null);
        else{
            Object file=fileClass.getField("INSTANCE").get(null);if(file==null)throw new IllegalStateException("Quest file not ready");
            Object q=fileClass.getMethod("getQuest",long.class).invoke(file,Long.parseUnsignedLong(quest,16));
            if(q==null)throw new IllegalArgumentException("Unknown quest ID");
            fileClass.getMethod("openGui",Class.forName("dev.ftb.mods.ftbquests.quest.Quest"),boolean.class).invoke(null,q,false);
        }
    }
}
