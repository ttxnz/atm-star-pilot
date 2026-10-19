package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.*;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Reads only the charger item that its renderer and update packet publish; uses normal insert/retrieve clicks. */
public final class ChargerBatch {
    private static final PlanModel.Material INPUT=new PlanModel.Material("ae2:certus_quartz_crystal",""),OUTPUT=new PlanModel.Material("ae2:charged_certus_quartz_crystal","");
    private static Object tile(BlockPos p){ServerRules.checkBuild(p);if(!LoadedWorld.has(p))throw new IllegalStateException("Charger chunk not received");var tile=Actions.mc().level.getBlockEntity(p);
        if(tile==null||!tile.getClass().getName().equals("appeng.blockentity.misc.ChargerBlockEntity"))throw new IllegalStateException("Own AE charger not observed at "+p);return tile;}
    private static ItemStack display(BlockPos p)throws Exception {Object t=tile(p),inventory=t.getClass().getMethod("getInternalInventory").invoke(t);
        return (ItemStack)Class.forName("appeng.api.inventories.InternalInventory",true,PilotJobs.class.getClassLoader()).getMethod("getStackInSlot",int.class).invoke(inventory,0);}
    private static Direction front(BlockPos p)throws Exception {Object t=tile(p);return (Direction)t.getClass().getMethod("getFront").invoke(t);}
    private static JsonObject view(BlockPos p)throws Exception {Object t=tile(p);return Jsons.obj("x",p.getX(),"y",p.getY(),"z",p.getZ(),"front",front(p).getName(),"working",t.getClass().getMethod("isWorking").invoke(t),"display",WorldView.stack(display(p)));}
    private static List<BlockPos> positions(JsonObject data){JsonArray values=data.getAsJsonArray("positions");if(values==null||values.isEmpty()||values.size()>16)throw new IllegalArgumentException("Observe 1..16 own chargers");
        List<BlockPos> result=new ArrayList<>();for(var value:values)result.add(Actions.pos(value.getAsJsonObject()));if(new HashSet<>(result).size()!=result.size())throw new IllegalArgumentException("Duplicate charger position");return result;}
    public static final class Inspect implements PilotController.Job {
        private final List<BlockPos> positions;public Inspect(JsonObject data){positions=positions(data);}
        public boolean tick(PilotController c)throws Exception {JsonArray chargers=new JsonArray();for(var p:positions)chargers.add(view(p));Files.writeString(c.directory.resolve("last-charger-inspection.json"),Jsons.GSON.toJson(Jsons.obj("chargers",chargers,
            "source","The one visible item and working flag published by the native Charger renderer/update packet; no private inventory or unsynchronized energy values")),StandardCharsets.UTF_8);return true;}
    }
    public static final class Run implements PilotController.Job {
        private final List<BlockPos> positions;private final boolean[] pending;private final int requested;private long inputBefore,baseline,target,operationBefore;
        private int delivered,received,index,selected,phase,wait,elapsed,pickupWait;private boolean collecting;private PilotController.Job child;
        public Run(JsonObject data){positions=positions(data);pending=new boolean[positions.size()];requested=Jsons.integer(data,"count",1);if(requested<1||requested>4096)throw new IllegalArgumentException("Charger batch count 1..4096 required");}
        public String description(){return "Parallel native charger batch: inputs "+delivered+"/"+requested+", collected "+received+"/"+requested;}
        private void report(PilotController c,String state)throws Exception {JsonArray chargers=new JsonArray();for(var p:positions)chargers.add(view(p));Files.writeString(c.directory.resolve("last-charger-batch.json"),Jsons.GSON.toJson(Jsons.obj(
            "observedAt",Instant.now().toString(),"state",state,"requested",requested,"inputsDelivered",delivered,"outputsReceived",received,"phase",phase,"inputBefore",inputBefore,"inputNow",WorldView.count(INPUT),
            "inventoryBefore",baseline,"targetInventory",target,"inventoryNow",WorldView.count(OUTPUT),"chargers",chargers)),StandardCharsets.UTF_8);}
        private BlockPos stance(BlockPos p)throws Exception {Direction front=front(p);if(front==Direction.DOWN)throw new IllegalStateException("Downward charger output is not supported by this pickup adapter");return p.relative(front);}
        private void selectInput(){var mc=Actions.mc();for(int i=0;i<36;i++)if(WorldView.material(mc.player.getInventory().getItem(i)).equals(INPUT)){
            if(i>=9){Actions.click(mc.player.inventoryMenu.containerId,i,8,ClickType.SWAP);i=8;}Actions.select(i);return;}throw new IllegalStateException("No unstaged certus crystal remains");}
        private void emptyHand(){for(int i=0;i<9;i++)if(Actions.mc().player.getInventory().getItem(i).isEmpty()){Actions.select(i);return;}throw new IllegalStateException("Need an empty hotbar slot for charger output retrieval");}
        public boolean tick(PilotController c)throws Exception {
            var mc=Actions.mc();for(var p:positions)tile(p);if(++elapsed>12000){report(c,"timed_out_needs_replan");throw new IllegalStateException("Charger batch timed out; staged inputs are not replayed");}if(wait-->0)return false;
            if(phase==0){Actions.closeMenu();var recipe=JeiBridge.entry("ae2:charger|ae2:charger/charged_certus_quartz_crystal").recipe();
                if(!recipe.type().equals("ae2:charger")||!recipe.deterministic()||recipe.inputs().size()!=1||recipe.outputs().size()!=1||!recipe.outputs().getFirst().material().equals(OUTPUT)||recipe.outputs().getFirst().count()!=1)throw new IllegalStateException("Live charger recipe changed");
                if(WorldView.count(INPUT)<requested)throw new IllegalStateException("The full charger batch must be present before staging");
                for(var p:positions){if(!display(p).isEmpty())throw new IllegalStateException("A charger contains an unfinished batch; inspect rather than refill");var stand=stance(p);if(!LoadedWorld.has(stand))throw new IllegalStateException("Output stance not loaded");}
                inputBefore=WorldView.count(INPUT);baseline=WorldView.count(OUTPUT);target=baseline+requested;phase=1;report(c,"initialized");}
            if(phase==1){
                if(received==requested){if(WorldView.count(OUTPUT)!=target||WorldView.count(INPUT)!=inputBefore-requested)throw new IllegalStateException("Charger inventory totals differ from the batch");for(var p:positions)if(!display(p).isEmpty())throw new IllegalStateException("Charger display not empty at completion");report(c,"completed_verified");return true;}
                boolean found=false;for(int offset=0;offset<positions.size();offset++){int candidate=(index+offset)%positions.size();ItemStack item=display(positions.get(candidate));
                    if(!item.isEmpty()&&WorldView.material(item).equals(OUTPUT)){if(!pending[candidate])throw new IllegalStateException("An unaccounted charger output appeared");selected=candidate;collecting=true;found=true;break;}
                    if(item.isEmpty()){if(pending[candidate])throw new IllegalStateException("A staged charger input disappeared before receipt");if(delivered<requested){selected=candidate;collecting=false;found=true;break;}}
                    else if(!WorldView.material(item).equals(INPUT)||!pending[candidate])throw new IllegalStateException("Unexpected charger display material");
                }
                if(!found){wait=3;return false;}var stand=stance(positions.get(selected));child=PilotJobs.create(Jsons.obj("type","walk","x",stand.getX(),"y",stand.getY(),"z",stand.getZ()));phase=2;
            }
            if(phase==2){if(!child.tick(c))return false;child=null;Actions.releaseControls();
                if(collecting){if(!WorldView.material(display(positions.get(selected))).equals(OUTPUT))throw new IllegalStateException("Charger output changed before retrieval");emptyHand();operationBefore=WorldView.count(OUTPUT);phase=5;pickupWait=0;
                    Actions.useBlock(positions.get(selected),front(positions.get(selected)));wait=10;return false;}
                if(!display(positions.get(selected)).isEmpty())throw new IllegalStateException("Empty charger changed before insertion");selectInput();phase=3;wait=3;return false;
            }
            if(phase==3){if(!WorldView.material(mc.player.getMainHandItem()).equals(INPUT)||!display(positions.get(selected)).isEmpty())throw new IllegalStateException("Prepared charger input changed");
                operationBefore=WorldView.count(INPUT);Actions.useBlock(positions.get(selected),front(positions.get(selected)));phase=4;wait=8;return false;}
            if(phase==4){var item=display(positions.get(selected));if(item.getCount()!=1||(!WorldView.material(item).equals(INPUT)&&!WorldView.material(item).equals(OUTPUT))||WorldView.count(INPUT)!=operationBefore-1)throw new IllegalStateException("Normal charger insertion not confirmed; click not repeated");
                pending[selected]=true;delivered++;index=(selected+1)%positions.size();phase=1;report(c,"input_confirmed");return false;}
            if(phase==5){long now=WorldView.count(OUTPUT);if(now!=operationBefore+1){if(now>operationBefore+1||++pickupWait>12){report(c,"pickup_not_confirmed");throw new IllegalStateException("Normal charged-crystal pickup not confirmed; inspect retained item");}wait=10;return false;}
                if(!display(positions.get(selected)).isEmpty())throw new IllegalStateException("Charger display remains occupied after retrieval");pending[selected]=false;received++;index=(selected+1)%positions.size();phase=1;report(c,"output_confirmed");return false;}
            return false;
        }
        public void cancel(){if(child!=null)child.cancel();Actions.releaseControls();try{report(PilotMod.CONTROLLER,"interrupted_needs_replan");}catch(Exception ignored){}}
    }
}
