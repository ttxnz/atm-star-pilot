package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.client.gui.components.*;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Configures only an observed own Pipez item extraction GUI through its native filter form. */
public final class PipeFilterAccess {
    private PipeFilterAccess(){}
    private static Class<?> api(String name)throws Exception{return Class.forName(name,true,PilotJobs.class.getClassLoader());}
    private static Object call(Object o,String name)throws Exception{return o.getClass().getMethod(name).invoke(o);}
    private static Object field(Object o,String name)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static Object type(Object pipe)throws Exception{var types=(Object[])call(pipe,"getPipeTypes");if(types.length!=1||!types[0].getClass().getName().equals("de.maxhenkel.pipez.blocks.tileentity.types.ItemPipeType"))throw new IllegalStateException("A single-type item pipe is required");return types[0];}
    private static Object property(Object tile,Direction side,String method)throws Exception{return tile.getClass().getMethod(method,Direction.class,api("de.maxhenkel.pipez.blocks.tileentity.types.PipeType")).invoke(tile,side,type(tile));}
    private static List<?> filters(Object tile,Direction side)throws Exception{return (List<?>)property(tile,side,"getFilters");}
    private static String exactPlainFilter(Object f)throws Exception{
        Object tag=call(f,"getTag"),metadata=call(f,"getMetadata");
        if(tag==null||!tag.getClass().getName().equals("de.maxhenkel.pipez.corelib.tag.SingleElementTag")||!(metadata instanceof CompoundTag n)||!n.isEmpty()||!(Boolean)call(f,"isExactMetadata")||(Boolean)call(f,"isInvert")||call(f,"getDestination")!=null)throw new IllegalStateException("Existing filter is not an exact plain-item whitelist entry; inspect before replacing it");
        return call(tag,"getName").toString();
    }
    private static Object guard(BlockPos expected,Direction side,String menuClass)throws Exception{
        var mc=Actions.mc();Object menu=mc.player.containerMenu;if(!menu.getClass().getName().equals(menuClass))throw new IllegalStateException("Expected native Pipez menu: "+menuClass);
        Object tile=call(menu,"getPipe");BlockPos p=((net.minecraft.world.level.block.entity.BlockEntity)tile).getBlockPos();ServerRules.checkBuild(p);Actions.checkReach(p);
        if(!p.equals(expected)||call(menu,"getSide")!=side)throw new IllegalStateException("Pipe filter GUI target changed");
        if(!mc.player.containerMenu.getCarried().isEmpty())throw new IllegalStateException("Pipe filter editing requires an empty cursor");type(tile);return tile;
    }
    public static JsonObject menuView(Object menu)throws Exception{
        Object tile=call(menu,"getPipe");Direction side=(Direction)call(menu,"getSide");JsonArray rows=new JsonArray();
        for(Object f:filters(tile,side))rows.add(call(f,"toNbt").toString());
        return Jsons.obj("filterMode",property(tile,side,"getFilterMode").toString(),"filters",rows);
    }
    public static final class ClearPlainWhitelist implements PilotController.Job {
        private final BlockPos pos;private final Direction side;private final Set<String> expected=new HashSet<>();
        private UUID pending;private int elapsed,phase;
        public ClearPlainWhitelist(JsonObject d){
            pos=Actions.pos(d);side=Direction.valueOf(Jsons.str(d,"side","").toUpperCase(Locale.ROOT));
            var rows=d.getAsJsonArray("expectedItems");if(rows==null||rows.isEmpty()||rows.size()>32)throw new IllegalArgumentException("Explicit old plain whitelist items are required");
            for(var v:rows)if(!expected.add(v.getAsString()))throw new IllegalArgumentException("Duplicate old whitelist item");
        }
        public String description(){return "Remove the observed obsolete plain whitelist through native Pipez GUI buttons";}
        public boolean tick(PilotController c)throws Exception{
            if(++elapsed>400)throw new IllegalStateException("Native filter removal timed out; pending removal was not repeated");
            var mc=Actions.mc();Object tile=guard(pos,side,"de.maxhenkel.pipez.gui.ExtractContainer");
            if(mc.screen==null||!mc.screen.getClass().getName().equals("de.maxhenkel.pipez.gui.ExtractScreen"))throw new IllegalStateException("Native extraction screen changed");
            if(!property(tile,side,"getFilterMode").toString().equals("WHITELIST"))throw new IllegalStateException("Expected the observed old whitelist mode");
            List<?> rows=filters(tile,side);Set<String> actual=new HashSet<>();
            for(Object f:rows)if(!actual.add(exactPlainFilter(f)))throw new IllegalStateException("Duplicate native whitelist entry");
            if(!expected.containsAll(actual)||elapsed==1&&!expected.equals(actual))throw new IllegalStateException("The observed old whitelist changed");
            if(pending!=null){
                for(Object f:rows)if(pending.equals(call(f,"getId")))return false;
                pending=null;phase=0;
            }
            if(rows.isEmpty()){
                Files.writeString(c.directory.resolve("last-pipe-cleared.json"),Jsons.GSON.toJson(Jsons.obj("observedAt",Instant.now().toString(),"x",pos.getX(),"y",pos.getY(),"z",pos.getZ(),"side",side.getName(),"actual",menuView(mc.player.containerMenu))),StandardCharsets.UTF_8);return true;
            }
            if(phase==0){
                Object list=field(mc.screen,"filterList");var selected=list.getClass().getDeclaredField("selected");selected.setAccessible(true);selected.setInt(list,0);phase=1;return false;
            }
            var button=(Button)field(mc.screen,"removeFilterButton");if(!button.active)return false;
            pending=(UUID)call(rows.getFirst(),"getId");button.onPress();return false;
        }
    }
    public static final class Whitelist implements PilotController.Job {
        private final BlockPos pos;private final Direction side;private final LinkedHashSet<String> items=new LinkedHashSet<>();private String pending="";private int phase,elapsed,wait;
        public Whitelist(JsonObject d){pos=Actions.pos(d);side=Direction.valueOf(Jsons.str(d,"side","").toUpperCase(Locale.ROOT));var rows=d.getAsJsonArray("items");if(rows==null||rows.isEmpty()||rows.size()>32)throw new IllegalArgumentException("Whitelist requires 1..32 exact plain material IDs");for(var v:rows)if(!items.add(v.getAsString()))throw new IllegalArgumentException("Duplicate whitelist material");}
        public String description(){return "Configure native exact-component Pipez whitelist at "+pos.toShortString();}
        private void save(PilotController c,String state,Object tile)throws Exception{Files.writeString(c.directory.resolve("last-pipe-whitelist.json"),Jsons.GSON.toJson(Jsons.obj("observedAt",Instant.now().toString(),"state",state,"dimension",Actions.mc().level.dimension().location().toString(),"x",pos.getX(),"y",pos.getY(),"z",pos.getZ(),"side",side.getName(),"requested",items,"actual",menuView(Actions.mc().player.containerMenu))),StandardCharsets.UTF_8);}
        public boolean tick(PilotController c)throws Exception{
            var mc=Actions.mc();if(++elapsed>800)throw new IllegalStateException("Native pipe filter configuration timed out; no pending submit was repeated");
            if(wait-->0)return false;
            if(phase==0){
                Object tile=guard(pos,side,"de.maxhenkel.pipez.gui.ExtractContainer");
                Object upgrade=mc.player.containerMenu.getSlot(0).getItem().getItem();if(!upgrade.getClass().getName().equals("de.maxhenkel.pipez.items.UpgradeItem")||!(Boolean)call(call(upgrade,"getTier"),"canChangeFilter"))throw new IllegalStateException("Install a real filtering-capable Pipez upgrade first");
                if(!property(tile,side,"getFilterMode").toString().equals("WHITELIST"))throw new IllegalStateException("Native pipe must already be in whitelist mode");
                var found=new HashSet<String>();for(Object f:filters(tile,side)){String id=exactPlainFilter(f);if(!items.contains(id)||!found.add(id))throw new IllegalStateException("Unexpected or duplicate existing whitelist entry");}
                if(found.equals(items)){save(c,"completed_verified",tile);return true;}
                for(String id:items){var key=ResourceLocation.parse(id);if(!BuiltInRegistries.ITEM.containsKey(key))throw new IllegalArgumentException("Unknown whitelist item "+id);var stack=new ItemStack(BuiltInRegistries.ITEM.get(key));if(stack.getMaxStackSize()<2||!stack.getComponentsPatch().isEmpty())throw new IllegalArgumentException("Whitelist admits only plain stackable material defaults: "+id);if(!found.contains(id)&&pending.isEmpty())pending=id;}
                Object fresh=api("de.maxhenkel.pipez.ItemFilter").getConstructor().newInstance();Object packet=api("de.maxhenkel.pipez.net.EditFilterMessage").getConstructor(api("de.maxhenkel.pipez.Filter"),int.class).newInstance(fresh,0);PacketDistributor.sendToServer((CustomPacketPayload)packet);phase=1;return false;
            }
            if(phase==1){
                if(!mc.player.containerMenu.getClass().getName().equals("de.maxhenkel.pipez.gui.FilterContainer"))return false;
                guard(pos,side,"de.maxhenkel.pipez.gui.FilterContainer");if(mc.screen==null||!mc.screen.getClass().getName().equals("de.maxhenkel.pipez.gui.FilterScreen"))return false;
                ((EditBox)field(mc.screen,"item")).setValue(pending);((EditBox)field(mc.screen,"nbt")).setValue("{}");phase=2;wait=4;return false;
            }
            if(phase==2){
                guard(pos,side,"de.maxhenkel.pipez.gui.FilterContainer");Object screen=mc.screen;if(screen==null||!screen.getClass().getName().equals("de.maxhenkel.pipez.gui.FilterScreen"))throw new IllegalStateException("Native filter form changed");
                Object f=call(mc.player.containerMenu,"getFilter");if(!(Boolean)call(f,"isExactMetadata")){var exact=(AbstractButton)field(screen,"nbtButton");if(!exact.active)throw new IllegalStateException("Native exact-metadata checkbox is unavailable");exact.onPress();}
                if(!pending.equals(exactPlainFilter(f)))throw new IllegalStateException("Native form does not match the requested plain material");
                var submit=(Button)field(screen,"submitButton");if(!submit.active)throw new IllegalStateException("Native filter form rejected the input");submit.onPress();phase=3;return false;
            }
            if(!mc.player.containerMenu.getClass().getName().equals("de.maxhenkel.pipez.gui.ExtractContainer"))return false;
            Object tile=guard(pos,side,"de.maxhenkel.pipez.gui.ExtractContainer");boolean observed=false;for(Object f:filters(tile,side))if(pending.equals(exactPlainFilter(f)))observed=true;
            if(!observed)return false;save(c,"entry_verified",tile);pending="";phase=0;wait=2;return false;
        }
    }
}
