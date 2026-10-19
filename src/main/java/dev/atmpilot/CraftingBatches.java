package dev.atmpilot;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.PacketDistributor;

/** The same bounded CRAFT_SHIFT packet used by AE's normal shift-click, only when a full stack is wanted. */
public final class CraftingBatches {
    private CraftingBatches(){}
    public static void takeResult(AbstractContainerMenu menu,int index,long remaining){
        var mc=Actions.mc();ServerRules.checkScreenAction();
        if(menu!=mc.player.containerMenu||index!=CraftingMenus.resultIndex(menu)
            ||!menu.getClass().getName().equals("appeng.menu.me.items.CraftingTermMenu")
            ||mc.screen==null||!mc.screen.getClass().getName().equals("appeng.client.gui.me.items.CraftingTermScreen"))
            throw new IllegalStateException("The own AE crafting screen and result must remain unchanged");
        var result=menu.getSlot(index).getItem();
        if(result.isEmpty()||!menu.getCarried().isEmpty()||Screen.hasShiftDown()||InputConstants.isKeyDown(mc.getWindow().getWindow(),32))
            throw new IllegalStateException("Release crafting modifiers and clear the cursor before an AE batch");
        try{
            Class<?> actionType=Class.forName("appeng.helpers.InventoryAction");
            boolean full=remaining>=result.getMaxStackSize();
            Object action=actionType.getField(full?"CRAFT_SHIFT":"CRAFT_ITEM").get(null);
            Object packet=Class.forName("appeng.core.network.serverbound.InventoryActionPacket")
                .getConstructor(actionType,int.class,long.class).newInstance(action,index,0L);
            int clicks=full?1:(int)Math.min(16,Math.min((remaining+result.getCount()-1)/result.getCount(),result.getMaxStackSize()/result.getCount()));
            for(int i=0;i<clicks;i++)PacketDistributor.sendToServer((CustomPacketPayload)packet);
        }catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot dispatch the native AE shift-craft interaction",e);}
    }
}
