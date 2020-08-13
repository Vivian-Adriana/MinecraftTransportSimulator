package minecrafttransportsimulator.vehicles.main;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mcinterface.BuilderEntity;
import mcinterface.InterfaceAudio;
import mcinterface.WrapperEntity;
import mcinterface.WrapperNBT;
import mcinterface.WrapperPlayer;
import mcinterface.WrapperWorld;
import minecrafttransportsimulator.baseclasses.Damage;
import minecrafttransportsimulator.baseclasses.Point3d;
import minecrafttransportsimulator.dataclasses.MTSRegistry;
import minecrafttransportsimulator.items.packs.ItemInstrument;
import minecrafttransportsimulator.jsondefs.JSONVehicle.VehiclePart;
import minecrafttransportsimulator.rendering.components.LightType;
import minecrafttransportsimulator.sound.IRadioProvider;
import minecrafttransportsimulator.sound.Radio;
import minecrafttransportsimulator.sound.SoundInstance;
import minecrafttransportsimulator.systems.ConfigSystem;
import minecrafttransportsimulator.vehicles.parts.APart;
import minecrafttransportsimulator.vehicles.parts.PartEngine;
import minecrafttransportsimulator.vehicles.parts.PartGroundDevice;
import minecrafttransportsimulator.vehicles.parts.PartGun;
import minecrafttransportsimulator.vehicles.parts.PartInteractable;
import net.minecraft.item.ItemStack;

/**This class adds engine components for vehicles, such as fuel, throttle,
 * and electricity.  Contains numerous methods for gauges, HUDs, and fuel systems.
 * This is added on-top of the D level to keep the crazy movement calculations
 * separate from the vehicle power overhead bits.  This is the first level of
 * class that can be used for references in systems as it's the last common class for
 * vehicles.  All other sub-levels are simply functional building-blocks to keep this
 *  class from having 1000+ lines of code and to better segment things out.
 * 
 * @author don_bruce
 */
abstract class EntityVehicleE_Powered extends EntityVehicleD_Moving implements IRadioProvider{
	
	//External state control.
	public boolean hornOn;
	public boolean sirenOn;
	public boolean reverseThrust;
	public boolean gearUpCommand;
	public byte throttle;
	public double fuel;
	
	//Internal states.
	public byte totalGuns = 0;
	public short reversePercent;
	public int gearMovementTime;
	public double electricPower = 12;
	public double electricUsage;
	public double electricFlow;
	public String fluidName = "";
	public EntityVehicleF_Physics towedVehicle;
	public EntityVehicleF_Physics towedByVehicle;
	/**List containing all lights that are powered on (shining).  Created as a set to allow for add calls that don't add duplicates.**/
	public final Set<LightType> lightsOn = new HashSet<LightType>();
	
	//Collision maps.
	public final Map<Byte, ItemInstrument> instruments = new HashMap<Byte, ItemInstrument>();
	public final Map<Byte, PartEngine> engines = new HashMap<Byte, PartEngine>();
	public final List<PartGroundDevice> wheels = new ArrayList<PartGroundDevice>();
	public final List<PartGroundDevice> groundedWheels = new ArrayList<PartGroundDevice>();
	
	//Internal radio variables.
	private final Radio radio = new Radio(this);
	private final FloatBuffer soundPosition = ByteBuffer.allocateDirect(3*Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
	private final FloatBuffer soundVelocity = ByteBuffer.allocateDirect(3*Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
	
	public EntityVehicleE_Powered(BuilderEntity builder, WrapperWorld world, WrapperNBT data){
		super(builder, world, data);
		
		//Load simple variables.
		this.throttle = (byte) data.getInteger("throttle");
    	this.fuel = data.getDouble("fuel");
		this.electricPower = data.getDouble("electricPower");
		this.fluidName = data.getString("fluidName");
		
		//Load lights.
		lightsOn.clear();
		String lightsOnString = data.getString("lightsOn");
		while(!lightsOnString.isEmpty()){
			String lightName = lightsOnString.substring(0, lightsOnString.indexOf(','));
			for(LightType light : LightType.values()){
				if(light.name().equals(lightName)){
					lightsOn.add(light);
					break;
				}
			}
			lightsOnString = lightsOnString.substring(lightsOnString.indexOf(',') + 1);
		}
		
		//Load instruments.
		for(byte i = 0; i<definition.motorized.instruments.size(); ++i){
			String instrumentPackID = data.getString("instrument" + i + "_packID");
			String instrumentSystemName = data.getString("instrument" + i + "_systemName");
			if(!instrumentPackID.isEmpty()){
				ItemInstrument instrument = (ItemInstrument) MTSRegistry.packItemMap.get(instrumentPackID).get(instrumentSystemName);
				//Check to prevent loading of faulty instruments due to updates.
				if(instrument != null){
					instruments.put(i, instrument);
				}
			}
		}
	}
	
	@Override
	public void update(){
		super.update();
		if(fuel <= 0){
			fuel = 0;
			fluidName = "";
		}
		
		//Do trailer-specific logic, if we are one and towed.
		//Otherwise, do normal update logic for DRLs.
		if(definition.motorized.isTrailer){
			//Check to make sure vehicle isn't dead for some reason.
			if(towedByVehicle != null && towedByVehicle.isValid){
				towedByVehicle = null;
			}else{
				//If we are being towed update our lights to match the vehicle we are being towed by.
				//Also set the brake state to the same as the towing vehicle.
				//If we aren't being towed, set the parking brake.
				if(towedByVehicle != null){
					lightsOn.clear();
					lightsOn.addAll(towedByVehicle.lightsOn);
					parkingBrakeOn = false;
					brakeOn = towedByVehicle.brakeOn;
				}else{
					parkingBrakeOn = true;
				}
			}
		}else{
			//Turn on the DRLs if we have an engine on.
			lightsOn.remove(LightType.DAYTIMERUNNINGLIGHT);
			for(PartEngine engine : engines.values()){
				if(engine.state.running){
					lightsOn.add(LightType.DAYTIMERUNNINGLIGHT);
					break;
				}
			}
		}
		
		
		//Set electric usage based on light status.
		if(electricPower > 2){
			for(LightType light : lightsOn){
				if(light.hasBeam){
					electricUsage += 0.0005F;
				}
			}
		}
		electricPower = Math.max(0, Math.min(13, electricPower -= electricUsage));
		electricFlow = electricUsage;
		electricUsage = 0;
		
		//Adjust reverse thrust variables.
		if(reverseThrust && reversePercent < 20){
			++reversePercent;
		}else if(!reverseThrust && reversePercent > 0){
			--reversePercent;
		}
		
		//Adjust gear variables.
		if(gearUpCommand && gearMovementTime < definition.motorized.gearSequenceDuration){
			++gearMovementTime;
		}else if(!gearUpCommand && gearMovementTime > 0){
			--gearMovementTime;
		}
		
		//Populate grounded wheels.  Needs to be independent of non-wheeled ground devices.
		groundedWheels.clear();
		for(PartGroundDevice wheel : this.wheels){
			if(wheel.isOnGround()){
				groundedWheels.add(wheel);
			}
		}
		
		//Update sound variables.
		soundPosition.rewind();
		soundPosition.put((float) position.x);
		soundPosition.put((float) position.y);
		soundPosition.put((float) position.z);
		soundPosition.flip();
		soundVelocity.rewind();
		soundVelocity.put((float) motion.x);
		soundVelocity.put((float) motion.y);
		soundVelocity.put((float) motion.z);
		soundVelocity.flip();
	}
	
	@Override
	public boolean isLitUp(){
		return ConfigSystem.configObject.client.vehicleBlklt.value && (lightsOn.contains(LightType.DAYTIMERUNNINGLIGHT) ? lightsOn.size() > 1 : !lightsOn.isEmpty());
	}
	
	@Override
	public void destroyAtPosition(Point3d position){
		super.destroyAtPosition(position);
		//Spawn instruments in the world.
		for(ItemInstrument instrument : instruments.values()){
			ItemStack stack = new ItemStack(instrument);
			world.spawnItemStack(stack, null, position);
		}
		
		//Damage all riders, including the controller.
		WrapperPlayer controller = getController();
		Damage controllerCrashDamage = new Damage(definition.general.type + "crash", ConfigSystem.configObject.damage.crashDamageFactor.value*velocity*20, null, null);
		Damage passengerCrashDamage = new Damage(definition.general.type + "crash", ConfigSystem.configObject.damage.crashDamageFactor.value*velocity*20, null, controller);
		for(WrapperEntity rider : ridersToLocations.keySet()){
			if(rider.equals(controller)){
				rider.attack(controllerCrashDamage);
			}else{
				rider.attack(passengerCrashDamage);
			}
		}
		
		//Oh, and add explosions.  Because those are always fun.
		//Note that this is done after spawning all parts here and in the super call,
		//so although all parts are DROPPED, not all parts may actually survive the explosion.
		if(ConfigSystem.configObject.damage.explosions.value){
			double fuelPresent = this.fuel;
			for(APart part : parts){
				if(part instanceof PartInteractable){
					PartInteractable interactable = (PartInteractable) part;
					if(interactable.tank != null){
						for(Map<String, Double> fuelEntry : ConfigSystem.configObject.fuel.fuels.values()){
							if(fuelEntry.containsKey(interactable.tank.getFluid())){
								fuelPresent += interactable.tank.getFluidLevel()*fuelEntry.get(interactable.tank.getFluid());
								break;
							}
						}
					}
				}
			}
			world.spawnExplosion(this, position, fuelPresent/10000D + 1D, true);
		}
		
		//Finally, if we are being towed, unhook us from our tower.
		if(towedByVehicle != null){
			towedByVehicle.towedVehicle = null;
			towedByVehicle = null;
		}
	}
	
	@Override
	protected float getCurrentMass(){
		return (float) (super.getCurrentMass() + fuel/50);
	}
	
	@Override
	public void addPart(APart part, boolean ignoreCollision){
		super.addPart(part, ignoreCollision);
		if(part instanceof PartEngine){
			//Because parts is a list, the #1 engine will always come before the #2 engine.
			//We can use this to determine where in the list this engine needs to go.
			byte engineNumber = 0;
			for(VehiclePart packPart : definition.parts){
				for(String type : packPart.types){
					if(type.startsWith("engine")){
						if(part.placementOffset.x == packPart.pos[0] && part.placementOffset.y == packPart.pos[1] && part.placementOffset.z == packPart.pos[2]){
							engines.put(engineNumber, (PartEngine) part);
							return;
						}
						++engineNumber;
					}
				}
			}
		}else if(part instanceof PartGroundDevice){
			if(part.definition.ground.isWheel || part.definition.ground.isTread){
				wheels.add((PartGroundDevice) part);
			}
		}else if(part instanceof PartGun){
			++totalGuns;
		}
	}
	
	@Override
	public void removePart(APart part, Iterator<APart> iterator, boolean playBreakSound){
		super.removePart(part, iterator, playBreakSound);
		byte engineNumber = 0;
		for(VehiclePart packPart : definition.parts){
			for(String type : packPart.types){
				if(type.startsWith("engine")){
					if(part.placementOffset.x == packPart.pos[0] && part.placementOffset.y == packPart.pos[1] && part.placementOffset.z == packPart.pos[2]){
						engines.remove(engineNumber);
						return;
					}
					++engineNumber;
				}
			}
		}
		if(wheels.contains(part)){
			wheels.remove(part);
		}else if(part instanceof PartGun){
			--totalGuns;
		}
	}
	
	//-----START OF SOUND CODE-----
	@Override
	public void updateProviderSound(SoundInstance sound){
		if(isValid){
			sound.stop();
		}else if(sound.soundName.equals(definition.motorized.hornSound)){
			if(!hornOn){
				sound.stop();
			}
		}else if(sound.soundName.equals(definition.motorized.sirenSound)){
			if(!sirenOn){
				sound.stop();
			}
		}
	}
	
	@Override
	public void restartSound(SoundInstance sound){
		if(sound.soundName.equals(definition.motorized.hornSound)){
			InterfaceAudio.playQuickSound(new SoundInstance(this, definition.motorized.hornSound, true));
		}else if(sound.soundName.equals(definition.motorized.sirenSound)){
			InterfaceAudio.playQuickSound(new SoundInstance(this, definition.motorized.sirenSound, true));
		}
	}
    
	@Override
    public FloatBuffer getProviderPosition(){
		return soundPosition;
	}
    
	@Override
    public FloatBuffer getProviderVelocity(){
		return soundVelocity;
	}
	
	@Override
    public int getProviderDimension(){
		return world.getDimensionID();
	}
	
	@Override
	public Radio getRadio(){
		return radio;
	}
	
	@Override
	public void save(WrapperNBT data){
		super.save(data);
		data.setInteger("throttle", throttle);
		data.setDouble("fuel", fuel);
		data.setDouble("electricPower", electricPower);
		data.setString("fluidName", fluidName);
		
		String lightsOnString = "";
		for(LightType light : this.lightsOn){
			lightsOnString += light.name() + ",";
		}
		data.setString("lightsOn", lightsOnString);
		
		String[] instrumentsInSlots = new String[definition.motorized.instruments.size()];
		for(byte i=0; i<instrumentsInSlots.length; ++i){
			if(instruments.containsKey(i)){
				data.setString("instrument" + i + "_packID", instruments.get(i).definition.packID);
				data.setString("instrument" + i + "_systemName", instruments.get(i).definition.systemName);
			}
		}
	}
}
