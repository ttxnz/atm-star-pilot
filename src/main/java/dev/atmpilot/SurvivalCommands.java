package dev.atmpilot;

import com.google.gson.*;

/** Explicit user-authorized ordinary server commands, dispatched through the normal client chat connection. */
public final class SurvivalCommands {
    private static long lastSent;
    private SurvivalCommands(){}
    public static String validate(String input,JsonObject rules){
        String command=input.startsWith("/")?input.substring(1):input;
        if(!command.matches("(?:back|home(?: [A-Za-z0-9_-]{1,32})?)"))throw new IllegalArgumentException("Only /back and /home [name] are supported");
        JsonObject policy=rules.getAsJsonObject("survivalCommands");
        if(policy==null||!policy.has("allow"))throw new IllegalStateException("Server survival commands have not been authorized");
        String verb=command.split(" ",2)[0];boolean allowed=false;
        for(var value:policy.getAsJsonArray("allow"))if(value.isJsonPrimitive()&&value.getAsString().equals(verb))allowed=true;
        if(!allowed)throw new IllegalStateException("This survival command is not in the user's allowlist");return command;
    }
    public static void send(String input){
        var mc=Actions.mc();JsonObject rules=ServerRules.snapshot();String command=validate(input,rules);
        long now=System.nanoTime(),interval=Math.max(1,Jsons.integer(rules.getAsJsonObject("survivalCommands"),"minimumIntervalSeconds",10))*1_000_000_000L;
        if(lastSent!=0&&now-lastSent<interval)throw new SurvivalCommandCooldown(interval-(now-lastSent));
        if(!mc.player.isAlive()||!mc.player.containerMenu.getCarried().isEmpty())throw new IllegalStateException("Travel commands require a living player and an empty cursor");
        mc.getConnection().sendCommand(command);lastSent=now;
    }
}
