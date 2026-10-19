package dev.atmpilot;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.*;
import com.mojang.blaze3d.platform.InputConstants;

/** AE crafting results use the screen's ordinary CRAFT_ITEM interaction, not a vanilla pickup packet. */
public final class CraftingClicks {
    private CraftingClicks(){}
    public static void pickupResult(AbstractContainerMenu menu,int index){
        var mc=Actions.mc();ServerRules.checkScreenAction();
        if(menu!=mc.player.containerMenu||index!=CraftingMenus.resultIndex(menu))throw new IllegalStateException("Crafting result identity changed");
        if(!menu.getClass().getName().equals("appeng.menu.me.items.CraftingTermMenu")
            ||mc.screen==null||!mc.screen.getClass().getName().equals("appeng.client.gui.me.items.CraftingTermScreen"))
            throw new IllegalStateException("The own AE crafting screen must remain open");
        if(!menu.getCarried().isEmpty()||Screen.hasShiftDown()||InputConstants.isKeyDown(mc.getWindow().getWindow(),32))
            throw new IllegalStateException("A single AE craft requires an empty cursor and released crafting modifiers");
        try{
            var method=Class.forName("appeng.client.gui.AEBaseScreen").getDeclaredMethod("slotClicked",Slot.class,int.class,int.class,ClickType.class);
            method.setAccessible(true);method.invoke(mc.screen,menu.getSlot(index),index,0,ClickType.PICKUP);
        }catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot invoke the normal AE single-craft click",e);}
    }
}
