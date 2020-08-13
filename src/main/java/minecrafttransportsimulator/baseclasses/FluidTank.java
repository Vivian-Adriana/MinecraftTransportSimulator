package minecrafttransportsimulator.baseclasses;

import java.util.HashMap;
import java.util.Map;

import mcinterface.InterfaceNetwork;
import mcinterface.WrapperNBT;
import minecrafttransportsimulator.packets.instances.PacketFluidTankChange;

/**Basic fluid tanks class.  Class contains methods for filling and draining, as well as automatic
 * syncing of fluid levels across clients and servers.  This allows the tank to be put on any object
 * without the need to worry about packets getting out of whack.
 *
 * @author don_bruce
 */
public class FluidTank{
	public static final Map<Integer, FluidTank> createdTanks = new HashMap<Integer, FluidTank>();
	/**Internal counter for tank IDs.  Increments each time an tank is created, and only valid on the server.**/
	private static int idCounter = 0;
	
	public final int tankID;
	private final int maxLevel;
	private final boolean sendUpdatePackets;
	private String currentFluid;
	private int fluidLevel;
	private int fluidDispensed;
	
	public FluidTank(WrapperNBT data, int maxLevel, boolean sendUpdatePackets){
		this.tankID = data.getInteger("tankID") == 0 ? idCounter++ : data.getInteger("tankID"); 
		this.maxLevel = maxLevel;
		this.sendUpdatePackets = sendUpdatePackets;
		this.currentFluid = data.getString("currentFluid");
		this.fluidLevel = data.getInteger("fluidLevel");
		this.fluidDispensed = data.getInteger("fluidDispensed");
		createdTanks.put(tankID, this);
	}
	
	/**
	 *  Gets the current fluid level.
	 */
	public int getFluidLevel(){
		return fluidLevel;
	}
	
	/**
	 *  Gets the max fluid level.
	 */
	public int getMaxLevel(){
		return maxLevel;
	}
	
	/**
	 *  Gets the amount of fluid dispensed since the last call to {@link #resetAmountDispensed()} 
	 */
	public int getAmountDispensed(){
		return fluidDispensed;
	}
	
	/**
	 *  Resets the total fluid dispensed counter.
	 */
	public void resetAmountDispensed(){
		fluidDispensed = 0;
	}
	
	/**
	 *  Gets the name of the fluid in the tank.
	 *  If no fluid is in the tank, an empty string should be returned.
	 */
	public String getFluid(){
		return currentFluid;
	}
	
	/**
	 *  Sets the fluid in this tank.
	 */
	public void setFluid(String fluidName){
		this.currentFluid = fluidName;
	}
	
	/**
	 *  Tries to fill fluid in the tank, returning the amount
	 *  filled, up to the passed-in maxAmount.  If doFill is false, 
	 *  only the possible amount filled should be returned, and the 
	 *  internal state should be left as-is.  Return value is the
	 *  amount filled.
	 */
	public int fill(String fluid, int maxAmount, boolean doFill){
		if(currentFluid.isEmpty() || currentFluid.equals(fluid)){
			if(maxAmount >= getMaxLevel() - fluidLevel){
				maxAmount = getMaxLevel() - fluidLevel;
			}
			if(doFill){
				fluidLevel += maxAmount;
				if(currentFluid.isEmpty()){
					currentFluid = fluid;
				}
				//Send off packet now that we know what fluid we will have on this tank.
				if(sendUpdatePackets){
					InterfaceNetwork.sendToAllClients(new PacketFluidTankChange(this, maxAmount));
				}
			}
			return maxAmount;
		}else{
			return 0;
		}
	}
	
	/**
	 *  Tries to drain the fluid from the tank, returning the amount
	 *  drained, up to the passed-in maxAmount.  If doDrain is false, 
	 *  only the possible amount drained should be returned, and the 
	 *  internal state should be left as-is.  Return value is the
	 *  amount drained.
	 */
	public int drain(String fluid, int maxAmount, boolean doDrain){
		if(!currentFluid.isEmpty() && currentFluid.equals(fluid)){
			if(maxAmount >= fluidLevel){
				maxAmount = fluidLevel;
			}
			if(doDrain){
				//Need to send off packet before we remove fluid due to empty tank.
				if(sendUpdatePackets){
					InterfaceNetwork.sendToAllClients(new PacketFluidTankChange(this, -maxAmount));
				}
				fluidLevel -= maxAmount;
				if(fluidLevel == 0){
					currentFluid = "";
				}
			}
			return maxAmount;
		}else{
			return 0;
		}
	}
	
	/**
	 *  Saves tank data to the passed-in NBT.
	 */
	public void save(WrapperNBT data){
		data.setString("fluidName", currentFluid);
		data.setInteger("fluidLevel", fluidLevel);
		data.setInteger("fluidDispensed", fluidDispensed);
	}
}
