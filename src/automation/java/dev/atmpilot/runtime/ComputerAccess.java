package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;

/** Native CraftOS screen/file-drop adapter. Never accesses a server computer or inventory directly. */
public final class ComputerAccess {
    private ComputerAccess(){}
    private static Object call(Object target,String method)throws ReflectiveOperationException {
        return target.getClass().getMethod(method).invoke(target);
    }
    private static Class<?> menuType()throws ClassNotFoundException {
        return Class.forName("dan200.computercraft.shared.computer.inventory.AbstractComputerMenu",true,PilotJobs.class.getClassLoader());
    }
    private static Path binding(){return PilotMod.CONTROLLER.directory.resolve("last-computer-binding.json");}
    private static void ownComputer(BlockPos pos){
        var mc=Actions.mc();ServerRules.checkBuild(pos);Actions.checkReach(pos);
        if(!LoadedWorld.has(pos)||!BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString().equals("computercraft:computer_normal"))
            throw new IllegalStateException("An observed own normal computer is required");
    }
    private static JsonObject guard()throws Exception {
        var mc=Actions.mc();ServerRules.checkScreenAction();
        if(mc.screen==null||!mc.screen.getClass().getName().equals("dan200.computercraft.client.gui.ComputerScreen")||!menuType().isInstance(mc.player.containerMenu))
            throw new IllegalStateException("Open the bound own native ComputerCraft screen first");
        JsonObject saved=JsonParser.parseString(Files.readString(binding(),StandardCharsets.UTF_8)).getAsJsonObject();
        if(Jsons.integer(saved,"menuId",-1)!=mc.player.containerMenu.containerId||!Jsons.str(saved,"dimension","").equals(mc.level.dimension().location().toString()))
            throw new IllegalStateException("Computer menu changed; bind it again through a normal block interaction");
        ownComputer(Actions.pos(saved));
        if(!mc.player.containerMenu.getCarried().isEmpty())throw new IllegalStateException("Computer interaction requires an empty cursor");
        return saved;
    }
    public static final class Open implements PilotController.Job {
        private final BlockPos pos;private final Direction face;private int initial,elapsed;
        public Open(JsonObject data){pos=Actions.pos(data);face=Direction.byName(Jsons.str(data,"face","south"));if(face==null)throw new IllegalArgumentException("Computer face is required");}
        public String description(){return "Open and bind the own CraftOS terminal through its native block";}
        public boolean tick(PilotController controller)throws Exception {
            var mc=Actions.mc();ownComputer(pos);
            if(elapsed++==0){Actions.closeMenu();initial=mc.player.containerMenu.containerId;Actions.useBlock(pos,face);return false;}
            if(mc.player.containerMenu.containerId!=initial){
                if(mc.screen==null||!mc.screen.getClass().getName().equals("dan200.computercraft.client.gui.ComputerScreen")||!menuType().isInstance(mc.player.containerMenu))
                    throw new IllegalStateException("The block did not open a native normal computer");
                JsonObject saved=Jsons.obj("observedAt",Instant.now().toString(),"menuId",mc.player.containerMenu.containerId,"dimension",mc.level.dimension().location().toString(),"x",pos.getX(),"y",pos.getY(),"z",pos.getZ());
                Object tile=mc.level.getBlockEntity(pos);saved.addProperty("computerId",(Number)call(tile,"getComputerID"));
                Files.writeString(binding(),Jsons.GSON.toJson(saved),StandardCharsets.UTF_8);return true;
            }
            if(elapsed>150)throw new IllegalStateException("Native computer did not open");return false;
        }
    }
    public static final class Inspect implements PilotController.Job {
        public String description(){return "Read the currently bound native CraftOS terminal display";}
        public boolean tick(PilotController controller)throws Exception {
            JsonObject report=guard();var menu=Actions.mc().player.containerMenu;
            report.addProperty("observedAt",Instant.now().toString());report.addProperty("on",(Boolean)call(menu,"isOn"));
            Object terminal=call(menu,"getTerminal");JsonArray lines=new JsonArray();
            if(terminal!=null){
                int height=((Number)call(terminal,"getHeight")).intValue();
                for(int i=0;i<Math.min(height,100);i++)lines.add(terminal.getClass().getMethod("getLine",int.class).invoke(terminal,i).toString());
            }
            report.add("lines",lines);Files.writeString(controller.directory.resolve("last-computer-inspection.json"),Jsons.GSON.toJson(report),StandardCharsets.UTF_8);return true;
        }
    }
    public static final class Upload implements PilotController.Job {
        private final String file;
        public Upload(JsonObject data){file=Jsons.str(data,"file","");if(!file.matches("[a-zA-Z0-9_-]{1,64}\\.lua"))throw new IllegalArgumentException("Use a named Lua file in computer-programs");}
        public String description(){return "Offer the prepared CraftOS program through native file drag-and-drop";}
        public boolean tick(PilotController controller)throws Exception {
            guard();Path root=controller.directory.resolve("computer-programs").toRealPath();Path path=root.resolve(file).toRealPath();
            if(!path.getParent().equals(root)||!Files.isRegularFile(path)||Files.size(path)>((Number)call(Actions.mc().player.containerMenu,"getUploadMaxSize")).longValue())
                throw new IllegalStateException("Prepared file is outside the program folder or exceeds the native upload limit");
            Actions.mc().screen.onFilesDrop(List.of(path));controller.note("Prepared file offered to the native ComputerCraft upload dialog: "+file);return true;
        }
    }
}
