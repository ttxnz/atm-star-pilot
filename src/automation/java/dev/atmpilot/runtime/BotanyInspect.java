package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/** Only the soil, seed and growth information also used by the pot renderer; no unopened output inventory. */
public final class BotanyInspect implements PilotController.Job {
    private final JsonArray positions;
    public BotanyInspect(JsonObject data){positions=data.getAsJsonArray("positions");if(positions==null||positions.isEmpty()||positions.size()>64)throw new IllegalArgumentException("Expected 1..64 observed pot positions");}
    public String description(){return "Read visible soil, crop and growth of own botany pots";}
    public boolean tick(PilotController controller)throws Exception{
        var mc=Actions.mc();JsonArray pots=new JsonArray();
        Class<?> type=Class.forName("net.darkhax.botanypots.common.impl.block.entity.BotanyPotBlockEntity",true,PilotJobs.class.getClassLoader());
        for(var value:positions){var pos=Actions.pos(value.getAsJsonObject());ServerRules.checkBuild(pos);
            if(!LoadedWorld.has(pos))throw new IllegalStateException("The pot chunk has not been received");
            Object pot=mc.level.getBlockEntity(pos);if(!type.isInstance(pot))throw new IllegalStateException("The observed botany pot is absent");
            JsonObject result=Jsons.obj("x",pos.getX(),"y",pos.getY(),"z",pos.getZ(),"hopper",type.getMethod("isHopper").invoke(pot),
                "growthTicks",type.getMethod("growthTime").invoke(pot),"requiredGrowthTicks",type.getMethod("getRequiredGrowthTicks").invoke(pot));
            result.add("soil",WorldView.stack((net.minecraft.world.item.ItemStack)type.getMethod("getSoilItem").invoke(pot)));
            result.add("seed",WorldView.stack((net.minecraft.world.item.ItemStack)type.getMethod("getSeedItem").invoke(pot)));pots.add(result);
        }
        Files.writeString(controller.directory.resolve("last-botany-inspection.json"),Jsons.GSON.toJson(Jsons.obj("observedAt",Instant.now().toString(),"dimension",mc.level.dimension().location().toString(),"pots",pots)),StandardCharsets.UTF_8);
        controller.note("Visible pot state saved; verify harvests separately in the own AE network");return true;
    }
}
