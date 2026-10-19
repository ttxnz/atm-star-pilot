package dev.atmpilot;

/** Pure control-ownership policy, independent of the running game and rendering classes. */
public final class SurvivalPolicy {
    private SurvivalPolicy(){}
    public static boolean manualReason(String value){return value.startsWith("Manual")||value.startsWith("F8")||value.equals("Stopped by controller")||value.equals("Control panel opened");}
    public static boolean eligible(boolean setting,boolean authorized,boolean manualHold,boolean alive,boolean recovering){return setting&&authorized&&!manualHold&&alive&&!recovering;}
}
