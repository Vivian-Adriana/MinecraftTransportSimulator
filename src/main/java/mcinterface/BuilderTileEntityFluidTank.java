package mcinterface;

import javax.annotation.Nullable;

import minecrafttransportsimulator.baseclasses.IFluidTankProvider;
import minecrafttransportsimulator.blocks.tileentities.components.ATileEntityBase;
import minecrafttransportsimulator.blocks.tileentities.components.ITileEntityTickable;
import minecrafttransportsimulator.jsondefs.AJSONItem;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.FluidEvent;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidTank;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.FluidTankProperties;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;

/**Builder for tile entities that contain fluids.  This builder does not tick.
 * If you need a tickable instance, use {@link BuilderTileEntityFluidTank.Tickable} instead.
 * Rather that implementing things directly, this builder just re-routes all calls the the tank-based builder.
 *
 * @author don_bruce
 */
public class BuilderTileEntityFluidTank<FluidTankTileEntity extends ATileEntityBase<? extends AJSONItem<? extends AJSONItem<?>.General>> & IFluidTankProvider> extends BuilderTileEntity<FluidTankTileEntity> implements IFluidTank, IFluidHandler{
	
	public BuilderTileEntityFluidTank(){
		super();
	}

	@Override
	public IFluidTankProperties[] getTankProperties(){
		return FluidTankProperties.convert(new FluidTankInfo[]{getInfo()});
	}

	@Override
	public FluidStack getFluid(){
		return tileEntity != null && !tileEntity.getTank().getFluid().isEmpty() ? new FluidStack(FluidRegistry.getFluid(tileEntity.getTank().getFluid()), tileEntity.getTank().getFluidLevel()) : null;
	}

	@Override
	public int getFluidAmount(){
		return tileEntity != null ? tileEntity.getTank().getFluidLevel() : 0;
	}

	@Override
	public int getCapacity(){
		return tileEntity != null ? tileEntity.getTank().getFluidLevel() : 0;
	}

	@Override
	public FluidTankInfo getInfo(){
		return new FluidTankInfo(this);
	}

	@Override
	public int fill(FluidStack stack, boolean doFill){
		if(tileEntity != null){
			int fillAmount = tileEntity.getTank().fill(stack.getFluid().getName(), stack.amount, doFill);
			if(fillAmount > 0 && doFill){
				FluidEvent.fireEvent(new FluidEvent.FluidFillingEvent(new FluidStack(stack.getFluid(), fillAmount), world, getPos(), this, fillAmount));
			}
			return fillAmount;
		}else{
			return 0;
		}
	}

	@Override
	public FluidStack drain(int maxDrain, boolean doDrain){
		if(getFluidAmount() > 0){
			return this.drain(new FluidStack(getFluid().getFluid(), maxDrain), doDrain);
		}
		return null;
	}
	
	@Override
	public FluidStack drain(FluidStack stack, boolean doDrain){
		int drainAmount = tileEntity != null ? tileEntity.getTank().drain(stack.getFluid().getName(), stack.amount, doDrain) : 0;
		if(drainAmount > 0 && doDrain){
			FluidEvent.fireEvent(new FluidEvent.FluidDrainingEvent(new FluidStack(stack.getFluid(), drainAmount), world, getPos(), this, drainAmount));
		}
		return new FluidStack(stack.getFluid(), drainAmount);
	}
	
    @Override
    public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing){
    	//Only let fluid be interacted with on the bottom face.
    	if(facing != null && facing.equals(EnumFacing.DOWN)){
    		return capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY || super.hasCapability(capability, facing);
    	}else{
    		return super.hasCapability(capability, facing);
    	}
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public <T> T getCapability(Capability<T> capability, EnumFacing facing){
    	if(facing != null && facing.equals(EnumFacing.DOWN)){
    		if(capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY){
    			return (T) this;
    		}
    	}
    	return super.getCapability(capability, facing);
    }
    
    
    /**Tickable builder for {@link BuilderTileEntityFluidTank}.
     *
     * @author don_bruce
     */
	public static class Tickable<TickableTileEntity extends ATileEntityBase<? extends AJSONItem<? extends AJSONItem<?>.General>> & IFluidTankProvider> extends BuilderTileEntityFluidTank<TickableTileEntity> implements ITickable{
		
		public Tickable(){
			super();
		}
		
		@Override
		public void update(){
			((ITileEntityTickable) tileEntity).update();
		}
	}
}
