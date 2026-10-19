package dev.atmpilot.vanilla;

interface VanillaTask {
    boolean tick() throws Exception;
    default void cancel(){dev.atmpilot.Actions.releaseControls();}
    default String describe(){return getClass().getSimpleName();}
}
