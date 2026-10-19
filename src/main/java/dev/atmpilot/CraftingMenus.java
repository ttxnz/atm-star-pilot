package dev.atmpilot;

import net.minecraft.world.inventory.*;
import java.util.List;

/** Fixed native crafting menus. AE's result is a semantic slot, not vanilla slot zero. */
public final class CraftingMenus {
    private CraftingMenus(){}
    public static boolean supported(AbstractContainerMenu menu){
        return menu instanceof CraftingMenu||menu instanceof InventoryMenu||menu.getClass().getName().equals("appeng.menu.me.items.CraftingTermMenu");
    }
    public static int resultIndex(AbstractContainerMenu menu){
        if(menu instanceof CraftingMenu||menu instanceof InventoryMenu)return 0;
        if(!supported(menu))throw new IllegalArgumentException("Unsupported crafting menu");
        try{
            Class<?> type=Class.forName("appeng.menu.SlotSemantic"),semantics=Class.forName("appeng.menu.SlotSemantics");
            Object result=semantics.getField("CRAFTING_RESULT").get(null);
            List<?> slots=(List<?>)menu.getClass().getMethod("getSlots",type).invoke(menu,result);
            if(slots.size()!=1)throw new IllegalStateException("Expected one live AE crafting result slot");
            Slot slot=(Slot)slots.getFirst();if(slot.index<0||slot.index>=menu.slots.size()||menu.getSlot(slot.index)!=slot)throw new IllegalStateException("AE result slot identity changed");return slot.index;
        }catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot locate the live AE crafting result",e);}
    }
}
