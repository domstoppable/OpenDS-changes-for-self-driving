/*
*  This file is part of OpenDS (Open Source Driving Simulator).
*  Copyright (C) 2016 Rafael Math
*
*  OpenDS is free software: you can redistribute it and/or modify
*  it under the terms of the GNU General Public License as published by
*  the Free Software Foundation, either version 3 of the License, or
*  (at your option) any later version.
*
*  OpenDS is distributed in the hope that it will be useful,
*  but WITHOUT ANY WARRANTY; without even the implied warranty of
*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*  GNU General Public License for more details.
*
*  You should have received a copy of the GNU General Public License
*  along with OpenDS. If not, see <http://www.gnu.org/licenses/>.
*/

package eu.opends.car;

import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Date;

import com.jme3.collision.CollisionResults;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Ray;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;

import eu.opends.basics.SimulationBasics;
import eu.opends.car.LightTexturesContainer.TurnSignalState;
import eu.opends.drivingTask.DrivingTask;
import eu.opends.drivingTask.scenario.ScenarioLoader;
import eu.opends.drivingTask.scenario.ScenarioLoader.CarProperty;
import eu.opends.drivingTask.settings.SettingsLoader;
import eu.opends.drivingTask.settings.SettingsLoader.Setting;
import eu.opends.environment.Crosswind;
import eu.opends.environment.TrafficLightCenter;
import eu.opends.main.SimulationDefaults;
import eu.opends.main.Simulator;
import eu.opends.simphynity.SimphynityController;
import eu.opends.tools.PanelCenter;
import eu.opends.tools.Util;
import eu.opends.traffic.FollowBox;
import eu.opends.traffic.FollowBoxSettings;
import eu.opends.traffic.PhysicalTraffic;
import eu.opends.traffic.TrafficObject;
import eu.opends.traffic.Waypoint;
import eu.opends.trafficObjectLocator.TrafficObjectLocator;
import com.jme3.scene.Node;

/**
 * Driving Car
 * 
 * @author Rafael Math
 */
public class SteeringCar extends Car implements TrafficObject
{
	// minimum steering percentage to be reached for switching off the turn signal automatically
	// when moving steering wheel back towards neutral position
	private float turnSignalThreshold = 0.25f;
	
    private TrafficObjectLocator trafficObjectLocator;
    private boolean handBrakeApplied = false;
    
    // Simphynity Motion Seat
    private SimphynityController simphynityController;
    
    // adaptive cruise control
	private boolean isAdaptiveCruiseControl = false;
	private float minLateralSafetyDistance;
	private float minForwardSafetyDistance;
	private float emergencyBrakeDistance;
	private boolean suppressDeactivationByBrake = false;
	
	// crosswind (will influence steering angle)
	private Crosswind crosswind = new Crosswind("left", 0, 0);
	
	private FollowBox followBox;
	
	private boolean isAutoPilot;
	private boolean accelerateBack = false;
	private boolean inCenterOfLane;
	
	// rotate steering wheel
	Vector3f rot_point = new Vector3f(2.0f, 2.0f, 2.0f);
    float angleST = 0;
    Quaternion initialPositionSteering = new Quaternion();
    Quaternion rotationSteering = new Quaternion();
	
	
	public SteeringCar(Simulator sim) 
	{		
		this.sim = sim;
		
		DrivingTask drivingTask = SimulationBasics.getDrivingTask();
		ScenarioLoader scenarioLoader = drivingTask.getScenarioLoader();
		
		initialPosition = scenarioLoader.getStartLocation();
		if(initialPosition == null)
			initialPosition = SimulationDefaults.initialCarPosition;
		
		this.initialRotation = scenarioLoader.getStartRotation();
		if(this.initialRotation == null)
			this.initialRotation = SimulationDefaults.initialCarRotation;
			
		// add start position as reset position
		Simulator.getResetPositionList().add(new ResetPosition(initialPosition,initialRotation));
		
		mass = scenarioLoader.getChassisMass();
		
		minSpeed = scenarioLoader.getCarProperty(CarProperty.engine_minSpeed, SimulationDefaults.engine_minSpeed);
		maxSpeed = scenarioLoader.getCarProperty(CarProperty.engine_maxSpeed, SimulationDefaults.engine_maxSpeed);
			
		decelerationBrake = scenarioLoader.getCarProperty(CarProperty.brake_decelerationBrake, 
				SimulationDefaults.brake_decelerationBrake);
		maxBrakeForce = 0.004375f * decelerationBrake * mass;
		
		decelerationFreeWheel = scenarioLoader.getCarProperty(CarProperty.brake_decelerationFreeWheel, 
				SimulationDefaults.brake_decelerationFreeWheel);
		maxFreeWheelBrakeForce = 0.004375f * decelerationFreeWheel * mass;
		
//		float brakePedalIntensity2 = 0;
//		float acceleratorPedalIntensity2 = 0;
		
		engineOn = scenarioLoader.getCarProperty(CarProperty.engine_engineOn, SimulationDefaults.engine_engineOn);
		if(!engineOn)
			showEngineStatusMessage(engineOn);
		
		Float lightIntensityObj = scenarioLoader.getCarProperty(CarProperty.light_intensity, SimulationDefaults.light_intensity);
		if(lightIntensityObj != null)
			lightIntensity = lightIntensityObj;
		
		transmission = new Transmission(this);
		powerTrain = new PowerTrain(this);
		
		modelPath = scenarioLoader.getModelPath();
		
		init();

        // allows to place objects at current position
        trafficObjectLocator = new TrafficObjectLocator(sim, this);
        
        // load settings of adaptive cruise control
        isAdaptiveCruiseControl = scenarioLoader.getCarProperty(CarProperty.cruiseControl_acc, SimulationDefaults.cruiseControl_acc);
    	minLateralSafetyDistance = scenarioLoader.getCarProperty(CarProperty.cruiseControl_safetyDistance_lateral, SimulationDefaults.cruiseControl_safetyDistance_lateral);
    	minForwardSafetyDistance = scenarioLoader.getCarProperty(CarProperty.cruiseControl_safetyDistance_forward, SimulationDefaults.cruiseControl_safetyDistance_forward);
    	emergencyBrakeDistance = scenarioLoader.getCarProperty(CarProperty.cruiseControl_emergencyBrakeDistance, SimulationDefaults.cruiseControl_emergencyBrakeDistance);
    	suppressDeactivationByBrake = scenarioLoader.getCarProperty(CarProperty.cruiseControl_suppressDeactivationByBrake, SimulationDefaults.cruiseControl_suppressDeactivationByBrake);
    	
    	// if initialSpeed > 0 --> cruise control will be on at startup
    	targetSpeedCruiseControl = scenarioLoader.getCarProperty(CarProperty.cruiseControl_initialSpeed, SimulationDefaults.cruiseControl_initialSpeed);
		isCruiseControl = (targetSpeedCruiseControl > 0);
    	
		SettingsLoader settingsLoader = SimulationBasics.getSettingsLoader();
        if(settingsLoader.getSetting(Setting.Simphynity_enableConnection, SimulationDefaults.Simphynity_enableConnection))
		{
        	String ip = settingsLoader.getSetting(Setting.Simphynity_ip, SimulationDefaults.Simphynity_ip);
			if(ip == null || ip.isEmpty())
				ip = "127.0.0.1";
			int port = settingsLoader.getSetting(Setting.Simphynity_port, SimulationDefaults.Simphynity_port);
			
	    	simphynityController = new SimphynityController(sim, this, ip, port);
		}
        
        // AutoPilot **************************************************************	
        FollowBoxSettings followBoxSettings = scenarioLoader.getAutoPilotFollowBoxSettings();
        isAutoPilot = scenarioLoader.isAutoPilot();
        followBox = new FollowBox(sim, this, followBoxSettings, isAutoPilot);
        // AutoPilot **************************************************************	
	}


	public TrafficObjectLocator getObjectLocator()
	{
		return trafficObjectLocator;
	}
	
	
	public boolean isHandBrakeApplied()
	{
		return handBrakeApplied;
	}
	
	
	public void applyHandBrake(boolean applied)
	{
		handBrakeApplied = applied;
	}

	
	// start applying crosswind and return to 0 (computed in update loop)
	public void setupCrosswind(String direction, float force, int duration)
	{
		crosswind = new Crosswind(direction, force, duration);
	}
	
	
	Vector3f lastVelocity = new Vector3f(0,0,0);
	long m_nLastChangeTime = 0;
	
	// The function that turns the AutoPilot on/off
	public void setAutoPilot(Boolean isAutoPilot)
	{
		if(this.isAutoPilot == isAutoPilot)
			return;
		
		this.isAutoPilot = isAutoPilot;
		if(!isAutoPilot)
		{
			/*steer(0);
			unsteer();*/
//			float brakePedalIntensity2 = brakePedalIntensity;
//			float acceleratorPedalIntensity2 = acceleratorPedalIntensity;
			
			// When the Autopilot is turned on/off, reset the pedal intensities 
			brakePedalIntensity = 0;
			acceleratorPedalIntensity = 0;
			
//			brakePedalIntensity = brakePedalIntensity2;
//			acceleratorPedalIntensity = acceleratorPedalIntensity2;
//			
			//PanelCenter.getMessageBox().addMessage(String.valueOf(brakePedalIntensity)+"_" + String.valueOf(acceleratorPedalIntensity), 3);
			//PanelCenter.getMessageBox().addMessage(String.valueOf(acceleratorPedalIntensity), 3);
//			PanelCenter.getMessageBox().addMessage(String.valueOf(getSteeringWheelState()), 3);
			
			// Display text box when the Autopilot mode is toggled
			PanelCenter.getMessageBox().addMessage("________________________________________Auto Pilot off________________________________________", 3);
			
			long millisecs = System.currentTimeMillis();
			Simulator.getDrivingTaskLogger().reportText("Auto Pilot off", millisecs);
		}
		else
		{
			/*steer(0);
			unsteer();*/
			brakePedalIntensity = 0;
			acceleratorPedalIntensity = 0;
			
			PanelCenter.getMessageBox().addMessage("________________________________________Auto Pilot on________________________________________", 3);
			//PanelCenter.getMessageBox().addMessage(String.valueOf(brakePedalIntensity)+"_" + String.valueOf(acceleratorPedalIntensity), 3);
			long millisecs = System.currentTimeMillis();
			Simulator.getDrivingTaskLogger().reportText("_Auto Pilot on", millisecs);
		}
	}
	
	public boolean isAutoPilot()
	{
		return isAutoPilot;
	}
	
	// This is to put the car in Reverse mode, in case you need to drive backwards
	public void setAccelerateBack(Boolean accelerateBack)
	{
		this.accelerateBack = accelerateBack;
	}
	
	public boolean isAcceleratingBack()
	{
		return accelerateBack;
	}
	
	public boolean inCenterOfLane()
	{
		return inCenterOfLane;
	}
	
	// will be called, in every frame
	@Override
	public void update(float tpf, ArrayList<TrafficObject> vehicleList)
	{
		if(sim.getCar().getPosition().getX()>=2.2 && sim.getCar().getPosition().getX()<=2.21)
		{
			inCenterOfLane = true;
		}
		else
		{
			inCenterOfLane = false;
		}
		// AutoPilot **************************************************************
		if(!sim.isPause() && isAutoPilot)
		{
		if(inCenterOfLane)
		{
			// update steering
						Vector3f wayPoint = followBox.getPosition();
						steerTowardsPosition(wayPoint);
						
						// update speed
						updateSpeed(tpf, vehicleList);
			
		}
		else
		{
			//float ahead=sim.getCar().getPosition().getY()+30;
			Vector3f wayPoint = new Vector3f(followBox.getPosition().getX(),sim.getCar().getPosition().getY(),sim.getCar().getPosition().getZ()-10);
			// update steering
			//Vector3f wayPoint = followBox.getPosition();
			steerTowardsPosition(wayPoint);
						
			// update speed
			updateSpeed(tpf, vehicleList);
		}
		}		
		
		// update movement of follow box according to vehicle's position
		Vector3f vehicleCenterPos = centerGeometry.getWorldTranslation();
		followBox.update(vehicleCenterPos);
		// AutoPilot **************************************************************	
		
		
		if(!isAutoPilot)
		{
			// accelerate
			float pAccel = 0;
			if(!engineOn)
			{
				// apply 0 acceleration when engine not running
				pAccel = powerTrain.getPAccel(tpf, 0) * 30f;
			}
			else if(isAutoAcceleration && (getCurrentSpeedKmh() < minSpeed))
			{
				// apply maximum acceleration (= -1 for forward) to maintain minimum speed
				pAccel = powerTrain.getPAccel(tpf, -1) * 30f;
			}
			else if(isCruiseControl && (getCurrentSpeedKmh() < targetSpeedCruiseControl))
			{
				// apply maximum acceleration (= -1 for forward) to maintain target speed
				pAccel = powerTrain.getPAccel(tpf, -1) * 30f;
				
				if(isAdaptiveCruiseControl)
				{
					// lower speed if leading car is getting to close
					pAccel = getAdaptivePAccel(pAccel);
				}
			}
			else
			{
				// apply acceleration according to gas pedal state
				pAccel = powerTrain.getPAccel(tpf, acceleratorPedalIntensity) * 30f;
			}
			transmission.performAcceleration(pAccel);
		
			// brake lights
			setBrakeLight(brakePedalIntensity > 0);
			
			if(handBrakeApplied)
			{
				// hand brake
				carControl.brake(maxBrakeForce);
				PanelCenter.setHandBrakeIndicator(true);
			}
			else
			{
				// brake	
				float appliedBrakeForce = brakePedalIntensity * maxBrakeForce;
				float currentFriction = powerTrain.getFrictionCoefficient() * maxFreeWheelBrakeForce;
				carControl.brake(appliedBrakeForce + currentFriction);
				PanelCenter.setHandBrakeIndicator(false);
			}
			
			
		}
		
		// lights
		leftHeadLight.setColor(ColorRGBA.White.mult(lightIntensity));
        leftHeadLight.setPosition(carModel.getLeftLightPosition());
        leftHeadLight.setDirection(carModel.getLeftLightDirection());
        
        rightHeadLight.setColor(ColorRGBA.White.mult(lightIntensity));
        rightHeadLight.setPosition(carModel.getRightLightPosition());
        rightHeadLight.setDirection(carModel.getRightLightDirection());
        
        // cruise control indicator
        if(isCruiseControl)
        	PanelCenter.setCruiseControlIndicator(targetSpeedCruiseControl);
        else
        	PanelCenter.unsetCruiseControlIndicator();
        
        trafficObjectLocator.update();
        
        // switch off turn signal after turn        
        /*if(hasFinishedTurn())
        {
        	lightTexturesContainer.setTurnSignal(TurnSignalState.OFF);
        }*/
        
        lightTexturesContainer.update();
        
		steeringInfluenceByCrosswind = crosswind.getCurrentSteeringInfluence();

        updateFrictionSlip();

        updateWheel();
        
        if(simphynityController != null)
        	simphynityController.update();
		    //simphynityController.updateNervtehInstructions();
        
        Boolean shadowModelActive = SimulationBasics.getSettingsLoader().getSetting(Setting.HighPolygon_carModel, SimulationDefaults.HighPolygon_carModel);
        
        try {
	        if (shadowModelActive){
		        Node steeringWheel = Util.findNode(carNode, "SteeringWheel");
		        
		        float currentPosition = getSteeringWheelStateNoNoise(sim.getCar().getSteeringWheelState());
		        //System.out.println("Position of steering wheel " + -currentPosition); //debug 
		
		        if (currentPosition == 0.0f){
		        	steeringWheel.setLocalRotation(initialPositionSteering);
		        }
		        else
		        {
		        	rotationSteering.fromAngleAxis(FastMath.DEG_TO_RAD*15, new Vector3f(0.0f, currentPosition, 0.0f ));
		        	steeringWheel.setLocalRotation(rotationSteering);
		        }
	        }
        } catch (Exception e){
        	e.printStackTrace();
        }
        
	
	}
	
	private float getSteeringWheelStateNoNoise(float currentValue){
		if  ( currentValue > -0.9f && currentValue < 0.9f)
		{	
			//PanelCenter.getMessageBox().addMessage(String.valueOf(currentValue), 1);
			return 0.0f;
		}
		else {						
			return currentValue;
			//return 0.0f; This part of the code is not the problem
		}
		
	}
	
	
	
    float leftWheelsPos = 2.2f;
    float backAxleHeight = -3.0f;
    float backAxlePos = 2.45f;
    long prevTime = 0;
    
    
	private void updateWheel() 
	{     
		long time = System.currentTimeMillis();
		if(time - prevTime > 1000)
		{/*
			Vector3f wheelDirection = new Vector3f(0, -1, 0);
			Vector3f wheelAxle = new Vector3f(-1, 0, 0);
			float wheelRadius = 0.5f;
			float suspensionLenght = 0.2f;
		
			carControl.removeWheel(3);
		
			backAxlePos += 0.05f;
		
			// add back left wheel
			Geometry geom_wheel_fl = Util.findGeom(carNode, "WheelBackLeft");
			geom_wheel_fl.setLocalScale(wheelRadius*2);
			geom_wheel_fl.center();
			BoundingBox box = (BoundingBox) geom_wheel_fl.getModelBound();
			carControl.addWheel(geom_wheel_fl.getParent(), 
        		box.getCenter().add(leftWheelsPos, backAxleHeight, backAxlePos),
                wheelDirection, wheelAxle, suspensionLenght, wheelRadius, true);

			System.out.println("backAxlePos: " + backAxlePos);
			
			prevTime = time;
			*/
		}
		//System.out.println("prevTime: " + prevTime + "  time: " + time);
	}


	private void updateFrictionSlip() 
	{
		/*
        // ice
        carControl.setRollInfluence(0, 0.5f); 
        carControl.setRollInfluence(1, 0.5f); 
        carControl.setRollInfluence(2, 0.5f); 
        carControl.setRollInfluence(3, 0.5f);
        
        carControl.setFrictionSlip(0, 1f); 
        carControl.setFrictionSlip(1, 1f); 
        carControl.setFrictionSlip(2, 1f); 
        carControl.setFrictionSlip(3, 1f); 
        */
	}


	private boolean hasStartedTurning = false;
	private boolean hasFinishedTurn() 
	{
		TurnSignalState turnSignalState = lightTexturesContainer.getTurnSignal();
		float steeringWheelState = getSteeringWheelState();
		
		if(turnSignalState == TurnSignalState.LEFT)
		{
			if(steeringWheelState > turnSignalThreshold)
				hasStartedTurning = true;
			else if(hasStartedTurning)
			{
				hasStartedTurning = false;
				return true;
			}
		}
		
		if(turnSignalState == TurnSignalState.RIGHT)
		{
			if(steeringWheelState < -turnSignalThreshold)
				hasStartedTurning = true;
			else if(hasStartedTurning)
			{
				hasStartedTurning = false;
				return true;
			}
		}
		
		return false;
	}


	// Adaptive Cruise Control ***************************************************	
	
	private float getAdaptivePAccel(float pAccel)
	{
		brakePedalIntensity = 0f;

		// check distance from traffic vehicles
		for(TrafficObject vehicle : PhysicalTraffic.getTrafficObjectList())
		{
			if(belowSafetyDistance(vehicle.getPosition()))
			{
				pAccel = 0;
			
				if(vehicle.getPosition().distance(getPosition()) < emergencyBrakeDistance)
					brakePedalIntensity = 1f;
			}
		}
		
		return pAccel;
	}


	public Float getLateralDistance(Vector3f obstaclePos) 
	{	
		float distance = obstaclePos.distance(getPosition());
		
		// angle between driving direction of traffic car and direction towards obstacle
		// (consider 3D space, because obstacle could be located on a bridge above traffic car)
		Vector3f carFrontPos = frontGeometry.getWorldTranslation();
		Vector3f carCenterPos = centerGeometry.getWorldTranslation();
		float angle = Util.getAngleBetweenPoints(carFrontPos, carCenterPos, obstaclePos, false);
		
		float lateralDistance = distance * FastMath.sin(angle);
		
		return lateralDistance;
	}
	
	// This is a pretty important function, allows you to get the distance between yourself and another obstacle/vehicle
	public Float getForwardDistance(Vector3f obstaclePos) 
	{	
		float distance = obstaclePos.distance(getPosition());
		
		// angle between driving direction of traffic car and direction towards obstacle
		// (consider 3D space, because obstacle could be located on a bridge above traffic car)
		Vector3f carFrontPos = frontGeometry.getWorldTranslation();
		Vector3f carCenterPos = centerGeometry.getWorldTranslation();
		float angle = Util.getAngleBetweenPoints(carFrontPos, carCenterPos, obstaclePos, false);
		
		float forwardDistance = distance * FastMath.cos(angle);
		
		return forwardDistance;
	}
	
	// This actually allows the other traffic to slow down, in case they're starting to get too close
	private boolean belowSafetyDistance(Vector3f obstaclePos) 
	{	
		float distance = obstaclePos.distance(getPosition());
		
		// angle between driving direction of traffic car and direction towards obstacle
		// (consider 3D space, because obstacle could be located on a bridge above traffic car)
		Vector3f carFrontPos = frontGeometry.getWorldTranslation();
		Vector3f carCenterPos = centerGeometry.getWorldTranslation();
		float angle = Util.getAngleBetweenPoints(carFrontPos, carCenterPos, obstaclePos, false);
		
		float lateralDistance = distance * FastMath.sin(angle);
		float forwardDistance = distance * FastMath.cos(angle);
		
		if((lateralDistance < minLateralSafetyDistance) && (forwardDistance > 0) && 
				(forwardDistance < Math.max(0.5f * getCurrentSpeedKmh(), minForwardSafetyDistance)))
		{
			return true;
		}
		
		return false;
	}


	public void increaseCruiseControl(float diff) 
	{
		targetSpeedCruiseControl = Math.min(targetSpeedCruiseControl + diff, 260.0f);	
	}


	public void decreaseCruiseControl(float diff) 
	{
		targetSpeedCruiseControl = Math.max(targetSpeedCruiseControl - diff, 0.0f);
	}

	
	public void disableCruiseControlByBrake() 
	{
		if(!suppressDeactivationByBrake)
			setCruiseControl(false);
	}
	// Adaptive Cruise Control ***************************************************


	
	public float getDistanceToRoadSurface() 
	{
		// reset collision results list
		CollisionResults results = new CollisionResults();

		// aim a ray from the car's center downwards to the road surface
		Ray ray = new Ray(getPosition(), Vector3f.UNIT_Y.mult(-1));

		// collect intersections between ray and scene elements in results list.
		sim.getSceneNode().collideWith(ray, results);
		
		// return the result
		for (int i = 0; i < results.size(); i++) 
		{
			// for each hit, we know distance, contact point, name of geometry.
			float dist = results.getCollision(i).getDistance();
			Geometry geometry = results.getCollision(i).getGeometry();

			if(geometry.getName().contains("CityEngineTerrainMate"))
				return dist - 0.07f;
		}
		
		return -1;
	}
	
	
	
	
	// AutoPilot *****************************************************************
	
	private void steerTowardsPosition(Vector3f wayPoint) 
	{
		// get relative position of way point --> steering direction
		// -1: way point is located on the left side of the vehicle
		//  0: way point is located in driving direction 
		//  1: way point is located on the right side of the vehicle
		int steeringDirection = getRelativePosition(wayPoint);
		
		// get angle between driving direction and way point direction --> steering intensity
		// only consider 2D space (projection of WPs to xz-plane)
		Vector3f carFrontPos = frontGeometry.getWorldTranslation();
		Vector3f carCenterPos = centerGeometry.getWorldTranslation();
		float steeringAngle = Util.getAngleBetweenPoints(carFrontPos, carCenterPos, wayPoint, true);
		
		// compute steering intensity in percent
		//  0     degree =   0%
		//  11.25 degree =  50%
		//  22.5  degree = 100%
		// >22.5  degree = 100%
		float steeringIntensity = Math.max(Math.min(4*steeringAngle/FastMath.PI,1f),0f);
		
		// apply steering instruction
		steer(steeringDirection*steeringIntensity);
		
		//System.out.println(steeringDirection*steeringIntensity);
	}
	
	private int getRelativePosition(Vector3f wayPoint)
	{
		// get vehicles center point and point in driving direction
		Vector3f frontPosition = frontGeometry.getWorldTranslation();
		Vector3f centerPosition = centerGeometry.getWorldTranslation();
		
		// convert Vector3f to Point2D.Float, as needed for Line2D.Float
		Point2D.Float centerPoint = new Point2D.Float(centerPosition.getX(),centerPosition.getZ());
		Point2D.Float frontPoint = new Point2D.Float(frontPosition.getX(),frontPosition.getZ());
		
		// line in direction of driving
		Line2D.Float line = new Line2D.Float(centerPoint,frontPoint);
		
		// convert Vector3f to Point2D.Float
		Point2D point = new Point2D.Float(wayPoint.getX(),wayPoint.getZ());

		// check way point's relative position to the line
		if(line.relativeCCW(point) == -1)
		{
			// point on the left --> return -1
			return -1;
		}
		else if(line.relativeCCW(point) == 1)
		{
			// point on the right --> return 1
			return 1;
		}
		else
		{
			// point on line --> return 0
			return 0;
		}
	}

	
	private void updateSpeed(float tpf, ArrayList<TrafficObject> vehicleList) 
	{
		float targetSpeed = getTargetSpeed();
		
		//if(overwriteSpeed >= 0)
		//	targetSpeed = Math.min(targetSpeed, overwriteSpeed);
		
		// stop car in order to avoid collision with other traffic objects and driving car
		// also for red traffic lights
		if(obstaclesInTheWay(vehicleList))
			targetSpeed = 0;
		
		float currentSpeed = getCurrentSpeedKmh();
		
		//System.out.print(name + ": " + targetSpeed + " *** " + currentSpeed);
		
		
		// set pedal positions
		if(currentSpeed < targetSpeed)
		{
			// too slow --> accelerate
			setAcceleratorPedalIntensity(-1);
			setBrakePedalIntensity(0);
			//System.out.println("gas");
			//System.out.print(" *** gas");
		}
		else if(currentSpeed > targetSpeed+1)
		{
			// too fast --> brake
			
			// currentSpeed >= targetSpeed+3 --> brake intensity: 100%
			// currentSpeed == targetSpeed+2 --> brake intensity:  50%
			// currentSpeed <= targetSpeed+1 --> brake intensity:   0%
			float brakeIntensity = (currentSpeed - targetSpeed - 1)/2.0f;
			brakeIntensity = Math.max(Math.min(brakeIntensity, 1.0f), 0.0f);
			
			// formerly use
			//brakeIntensity = 1.0f;
			
			setBrakePedalIntensity(brakeIntensity);
			setAcceleratorPedalIntensity(0);
			
			//System.out.println("brake: " + brakeIntensity);
			//System.out.print(" *** brake");
		}
		else
		{
			// else release pedals
			setAcceleratorPedalIntensity(0);
			setBrakePedalIntensity(0);
			//System.out.print(" *** free");
		}
		
		
		
		// accelerate
		if(engineOn)
			//carControl.accelerate(acceleratorPedalIntensity * accelerationForce);
			transmission.performAcceleration(powerTrain.getPAccel(tpf, acceleratorPedalIntensity) * 30f);
		else
			//carControl.accelerate(0);
			transmission.performAcceleration(0);
		//System.out.print(" *** " + gasPedalPressIntensity * accelerationForce);
		
		// brake	
		float appliedBrakeForce = brakePedalIntensity * maxBrakeForce;
		float currentFriction = 0.2f * maxFreeWheelBrakeForce;
		carControl.brake(appliedBrakeForce + currentFriction);
		
		//System.out.print(" *** " + appliedBrakeForce + currentFriction);
		//System.out.println("");
	}


	public float getTargetSpeed() 
	{
		// maximum speed for current way point segment
		float regularSpeed = followBox.getSpeed();

		// reduced speed to reach next speed limit in time
		float reducedSpeed = followBox.getReducedSpeed();
		
		float targetSpeed = Math.max(Math.min(regularSpeed, reducedSpeed),0);
		
		// limit maximum speed to speed of steering car 
		//if(isSpeedLimitedToSteeringCar)
		//	targetSpeed = Math.min(sim.getCar().getCurrentSpeedKmh(), targetSpeed);
		
		return targetSpeed;
	}
	
	
	/**
	 * Returns the signum of the speed change between this and the previous way point: 
	 * 0 if speed has not changed (or no previous way point available), 
	 * 1 if speed has been increased,
	 * -1 if speed has been decreased.
	 * 
	 * @return
	 * 		The signum of the speed change between this and the previous way point
	 */
	public int getSpeedChange()
	{
		Waypoint previousWP = followBox.getPreviousWayPoint();
		Waypoint currentWP = followBox.getCurrentWayPoint();
		
		if(previousWP == null)
			return 0;
		else
			return (int) Math.signum(currentWP.getSpeed() - previousWP.getSpeed());
	}


	private boolean obstaclesInTheWay(ArrayList<TrafficObject> vehicleList)
	{
		// check distance from driving car
		if(obstacleTooClose(sim.getCar().getPosition()))
			return true;

		// check distance from other traffic (except oneself)
		for(TrafficObject vehicle : vehicleList)
		{
			if(obstacleTooClose(vehicle.getPosition()))
				return true;
		}
		
		// check if red traffic light ahead
		Waypoint nextWayPoint = followBox.getNextWayPoint();
		if(TrafficLightCenter.hasRedTrafficLight(nextWayPoint))
			if(obstacleTooClose(nextWayPoint.getPosition()))
				return true;
		
		return false;
	}


	private boolean obstacleTooClose(Vector3f obstaclePos)
	{
		float distanceToObstacle = obstaclePos.distance(getPosition());
		
		// angle between driving direction of traffic car and direction towards obstacle
		// (consider 3D space, because obstacle could be located on a bridge above traffic car)
		Vector3f carFrontPos = frontGeometry.getWorldTranslation();
		Vector3f carCenterPos = centerGeometry.getWorldTranslation();
		float angle = Util.getAngleBetweenPoints(carFrontPos, carCenterPos, obstaclePos, false);
		if(belowSafetyDistance(angle, distanceToObstacle))
			return true;

		// considering direction towards next way point (if available)
		Waypoint nextWP = followBox.getNextWayPoint();
		if(nextWP != null)
		{
			// angle between direction towards next WP and direction towards obstacle
			// (consider 3D space, because obstacle could be located on a bridge above traffic car)
			angle = Util.getAngleBetweenPoints(nextWP.getPosition(), carCenterPos, obstaclePos, false);
			if(belowSafetyDistance(angle, distanceToObstacle))
				return true;
		}
		return false;
	}
	
	
	private boolean belowSafetyDistance(float angle, float distance) 
	{	
		float lateralDistance = distance * FastMath.sin(angle);
		float forwardDistance = distance * FastMath.cos(angle);
		
		//if(name.equals("car1"))
		//	System.out.println(lateralDistance + " *** " + forwardDistance);
		
		float speedDependentForwardSafetyDistance = 0;
		
		//if(useSpeedDependentForwardSafetyDistance)
		//	speedDependentForwardSafetyDistance = 0.5f * getCurrentSpeedKmh();
		
		if((lateralDistance < minLateralSafetyDistance) && (forwardDistance > 0) && 
				(forwardDistance < Math.max(speedDependentForwardSafetyDistance , minForwardSafetyDistance)))
		{
			return true;
		}
		
		return false;
	}

	@Override
	public String getName() 
	{
		return "drivingCar";
	}


	@Override
	public void setToWayPoint(String wayPointID) 
	{
		int index = followBox.getIndexOfWP(wayPointID);
		if(index != -1)
			followBox.setToWayPoint(index);
		else
			System.err.println("Invalid way point ID: " + wayPointID);
	}

	
	// AutoPilot *****************************************************************


}
