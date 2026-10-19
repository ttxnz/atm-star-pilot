package dev.atmpilot.runtime;

import com.google.gson.*;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.CommandNode;
import dev.atmpilot.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/** The same read-only completion request as typing /home and pressing Tab; no command is executed. */
public final class CommandSuggestions implements PilotController.Job {
    private CompletableFuture<Suggestions> future;private int elapsed;private JsonArray nodes;private final String command;
    public CommandSuggestions(JsonObject data){command=Jsons.str(data,"command","home");if(!java.util.Set.of("home","warp","rtp").contains(command))throw new IllegalArgumentException("Only ordinary travel-command completion may be read");}
    public String description(){return "Read permitted home-command nodes and normal Tab suggestions";}
    private void tree(CommandNode<?> node,String path,int depth){if(depth>3)return;String full=path.isEmpty()?node.getName():path+" "+node.getName();nodes.add(Jsons.obj("path",full,"nodeClass",node.getClass().getSimpleName(),"executable",node.getCommand()!=null));for(var child:node.getChildren())tree(child,full,depth+1);}
    public boolean tick(PilotController c)throws Exception {
        var connection=Actions.mc().getConnection();if(connection==null)throw new IllegalStateException("No current server command tree");
        if(future==null){nodes=new JsonArray();var dispatcher=connection.getCommands();for(String name:new String[]{"home","back","homes","warp","rtp","sethome"}){var node=dispatcher.getRoot().getChild(name);if(node!=null)tree(node,"",0);}
            future=dispatcher.getRoot().getChild(command)==null?CompletableFuture.completedFuture(Suggestions.empty().join()):dispatcher.getCompletionSuggestions(dispatcher.parse(command+" ",connection.getSuggestionsProvider()));}
        if(!future.isDone()){if(++elapsed>200)throw new IllegalStateException("Home-name suggestions timed out; no command was executed");return false;}
        JsonArray suggestions=new JsonArray();for(var suggestion:future.join().getList())suggestions.add(Jsons.obj("text",suggestion.getText(),"tooltip",suggestion.getTooltip()==null?null:suggestion.getTooltip().getString()));
        Files.writeString(c.directory.resolve("last-"+command+"-suggestions.json"),Jsons.GSON.toJson(Jsons.obj("observedAt",Instant.now().toString(),"command",command,"nodes",nodes,"suggestions",suggestions,"note","Read-only Brigadier/normal server Tab completion. No chat or command was sent for execution.")),StandardCharsets.UTF_8);return true;
    }
}
