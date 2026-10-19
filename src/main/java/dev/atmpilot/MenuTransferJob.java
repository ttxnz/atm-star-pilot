package dev.atmpilot;

import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/** Exact normal slot transfer. Cancellation leaves the carried stack visible, never drops it. */
public final class MenuTransferJob implements PilotController.Job {
    private final int container,source,destination,quantity;
    private int phase,remaining;
    private long nextTick;
    private ItemStack identity=ItemStack.EMPTY;
    public MenuTransferJob(int container,int source,int destination,int quantity) {
        if(quantity<1||quantity>64||source==destination) throw new IllegalArgumentException("Transfer count 1..64 and different slots required");
        this.container=container;this.source=source;this.destination=destination;this.quantity=quantity;
    }
    @Override public String description() { return "Transfer "+quantity+" items from slot "+source+" to "+destination; }
    @Override public boolean tick(PilotController controller) {
        if(controller.ticks()<nextTick) return false;
        var mc=Actions.mc(); var menu=mc.player.containerMenu;
        if(menu.containerId!=container) throw new IllegalStateException("Transfer container changed");
        if(source<0||destination<0||source>=menu.slots.size()||destination>=menu.slots.size())
            throw new IllegalArgumentException("Invalid transfer slot");
        var from=menu.getSlot(source); var to=menu.getSlot(destination);
        if(phase==0) {
            if(!menu.getCarried().isEmpty()) throw new IllegalStateException("Transfer requires an empty cursor");
            if(from.getItem().isEmpty()||from.getItem().getCount()<quantity) throw new IllegalStateException("Source is short of items");
            identity=from.getItem().copyWithCount(1);
            if(!from.mayPickup(mc.player)||!to.mayPlace(identity)) throw new IllegalStateException("Slot rejects transfer");
            if(!to.getItem().isEmpty()&&!ItemStack.isSameItemSameComponents(to.getItem(),identity))
                throw new IllegalStateException("Destination contains another material");
            int occupied=to.getItem().isEmpty()?0:to.getItem().getCount();
            if(to.getMaxStackSize(identity)-occupied<quantity) throw new IllegalStateException("Destination has insufficient capacity");
            Actions.click(container,source,0,ClickType.PICKUP); phase=1; remaining=quantity;
        } else if(phase==1) {
            ItemStack carried=menu.getCarried();
            if(carried.isEmpty()||!ItemStack.isSameItemSameComponents(carried,identity)||carried.getCount()<remaining)
                throw new IllegalStateException("Pickup did not produce the expected carried stack");
            if(carried.getCount()==remaining) { Actions.click(container,destination,0,ClickType.PICKUP);remaining=0;phase=2; }
            else {
                for(int clicks=0;clicks<16&&remaining>0;clicks++) {
                    carried=menu.getCarried();
                    if(carried.isEmpty()||!ItemStack.isSameItemSameComponents(carried,identity)||carried.getCount()<remaining)
                        throw new IllegalStateException("Transfer cursor changed during batch; inspect before retrying");
                    if(carried.getCount()==remaining){Actions.click(container,destination,0,ClickType.PICKUP);remaining=0;}
                    else {Actions.click(container,destination,1,ClickType.PICKUP);remaining--;}
                }
                if(remaining==0)phase=2;
            }
        } else if(phase==2) {
            if(!menu.getCarried().isEmpty()) {
                if(!ItemStack.isSameItemSameComponents(menu.getCarried(),identity)) throw new IllegalStateException("Carried material changed");
                if(!from.getItem().isEmpty()&&!ItemStack.isSameItemSameComponents(from.getItem(),identity))
                    throw new IllegalStateException("Source changed while returning leftovers; cursor retained");
                Actions.click(container,source,0,ClickType.PICKUP);
            }
            phase=3;
        } else {
            if(!menu.getCarried().isEmpty()) throw new IllegalStateException("Leftovers were not returned; cursor retained");
            return true;
        }
        nextTick=controller.ticks()+4; return false;
    }
}
