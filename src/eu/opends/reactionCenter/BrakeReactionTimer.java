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

package eu.opends.reactionCenter;

import java.util.Iterator;
import java.util.Map;
import java.util.Vector;
import java.util.Map.Entry;

import com.jme3.math.Vector3f;
import com.jme3.scene.Spatial;

import eu.opends.audio.AudioCenter;
import eu.opends.environment.LaneLimit;
import eu.opends.jasperReport.ReactionLogger;
import eu.opends.main.Simulator;
import eu.opends.tools.Util;
import eu.opends.traffic.PhysicalTraffic;
import eu.opends.traffic.TrafficObject;

/**
 * 
 * @author Rafael Math
 */

// Also read the LaneChangeReactionTimer comments, a lot of them apply to this one too
public class BrakeReactionTimer extends ReactionTimer 
{
	private long timer;
	private boolean timerSet = false;
	private float targetSpeed; 
	private boolean mustPressBrakePedal;
	private boolean hasPressedBrakepedal;
	private long startTime;
	private float taskCompletionTime;
	private Vector3f startPosition;
	private float taskCompletionDistance;
	private boolean allowLaneChange;
	private float holdSpeedFor;
	private String failSound;
	private String successSound;
	private String startLane;
	private String leadVehicle;
	private String leadObstacle;
	private Vector TTC;
	private float avgTTC;
	private float minTTC;
	
	private boolean soundTimerIsActive = false;
	
	
	public BrakeReactionTimer(Simulator sim, ReactionLogger reactionLogger, long experimentStartTime, 
			String timerID, int index) 
	{
		super(sim, reactionLogger, experimentStartTime, timerID, index);
	}

	
	public void setup(String newReactionGroupID, float startSpeed,	float targetSpeed, boolean mustPressBrakePedal,
			float taskCompletionTime, float taskCompletionDistance, boolean allowLaneChange, 
			float holdSpeedFor, String failSound, String successSound, String leadVehicle, String leadObstacle, String newComment) 
	{
		// check pre-condition
		if(sim.getCar().getCurrentSpeedKmh() >= startSpeed)
		{
			super.setup(newReactionGroupID, newComment);
			
			this.targetSpeed = targetSpeed;
			this.mustPressBrakePedal = mustPressBrakePedal;
			this.startTime = System.currentTimeMillis();
			this.taskCompletionTime = taskCompletionTime;
			this.startPosition = sim.getCar().getPosition();
			this.taskCompletionDistance = taskCompletionDistance;
			this.allowLaneChange = allowLaneChange;
			this.holdSpeedFor = holdSpeedFor;
			this.failSound = failSound;
			this.successSound = successSound;
			this.startLane = getCurrentLane();
			this.hasPressedBrakepedal = false;
			this.leadVehicle = leadVehicle;
			this.leadObstacle = leadObstacle;
			this.TTC= new Vector();
			this.avgTTC = 0; 
			this.minTTC = 0;
			
			trialLogger.setTask(1);
			
			timerIsActive = true;
			soundTimerIsActive = false;
		}
		else
		{
			System.err.println("Not above start speed " + startSpeed + "! Currently: " + sim.getCar().getCurrentSpeedKmh());
			
			// play sound when time/distance has been passed
			this.startTime = System.currentTimeMillis();
			this.taskCompletionTime = taskCompletionTime;
			this.startPosition = sim.getCar().getPosition();
			this.taskCompletionDistance = taskCompletionDistance;
			this.failSound = failSound;
			
			soundTimerIsActive = true;
		}
	}


	public void reportMissedReaction()
	{
		super.reportMissedReaction();
		//System.err.println("MISSED");

		// play fail sound
		AudioCenter.playSound(failSound);
	}
	

	public void update()
	{
		//super.update();
		
		if(timerIsActive)
		{
			// monitor whether brake pedal has been pressed
			hasPressedBrakepedal = (hasPressedBrakepedal || (sim.getCar().getBrakePedalIntensity() > 0));
			
			long currentTime = System.currentTimeMillis();

			if(leadVehicle.equals(""))
				TTC.add(getTTC(getObstacleLoc(leadObstacle)));
			else
				TTC.add(getTTC(getVehicleLoc(leadVehicle)));
			
			//System.out.println(TTC);
			
			
			if(hasChangedLanes())
				trialLogger.setAdditional_reaction(1);
			
			if(sim.getCar().getAcceleratorPedalIntensity() == 0)
				trialLogger.setBrakeRT_noGas((int)(currentTime - startTime));
			
			if(sim.getCar().getBrakePedalIntensity() > 0)
				trialLogger.setBrakeRT_StartBrake((int)(currentTime - startTime));
			
			if(sim.getCar().getBrakePedalIntensity() >= 0.8f)
				trialLogger.setBrakeRT_80pcBrake((int)(currentTime - startTime));
			
			if(timeExceeded() || distanceExceeded())
			{
				trialLogger.setStartTime(startTime);
				
				float sumTTC=(float) TTC.get(0);
				float minTTC=(float) TTC.get(0);
				
				for (int a=1;a<=TTC.size()-1;a++)
				{
					sumTTC=sumTTC+(float)TTC.get(a);
					
					if (minTTC>(float)TTC.get(a))
					{
						minTTC=(float)TTC.get(a);
					}
					
				}
				
				avgTTC=sumTTC/TTC.size();
				trialLogger.setAvgTTC(avgTTC);
				trialLogger.setMinTTC(minTTC);
				
				//if(minTTC<=0.0715)
					//trialLogger.setNoCollisions(1);
				
				reportMissedReaction();
				//System.out.println("MISSED1");
			}
			else if(hasChangedLanesWithoutPermission())
			{
				reportFailureReaction();
			}
			else if(sim.getCar().getCurrentSpeedKmh() <= targetSpeed)
			{
				if(!timerSet)
				{
					timer = System.currentTimeMillis();
					timerSet = true;
				}
				
				//System.err.println("-----------------------hold speed: " + (currentTime-timer));
				if((currentTime-timer >= holdSpeedFor))
				{
					if(mustPressBrakePedal)
					{
						if(hasPressedBrakepedal)
							reportCorrectReaction();
					}
					else
						reportCorrectReaction();
				}
			}
			else
			{
				timerSet = false;
			}
			
			
			long relativeStartTime = startTime - experimentStartTime;
			
			long holdSpeedOffset = 0l;
			if(timerSet)
				holdSpeedOffset = currentTime-timer;
			long reactionTime = currentTime - startTime - holdSpeedOffset;
			
			if(correctReactionReported)
			{
				trialLogger.setStartTime(startTime);
				//System.out.println("CORRECT");
				//System.err.println("CORRECT");
				float sumTTC=(float) TTC.get(0);
				float minTTC=(float) TTC.get(0);
				
				//System.out.println(sumTTC);
				//System.out.println(minTTC);
				//System.out.println(TTC.size());
				
				for (int a=1;a<=TTC.size()-1;a++)
				{
					sumTTC=sumTTC+(float)TTC.get(a);
					
					if (minTTC>(float)TTC.get(a))
					{
						minTTC=(float)TTC.get(a);
					}
					
				}
				
				avgTTC=sumTTC/TTC.size();
				trialLogger.setAvgTTC(avgTTC);
				trialLogger.setMinTTC(minTTC);
				
				//if(minTTC<=0.0715)
					//trialLogger.setNoCollisions(1);
				
				//System.out.println(avgTTC);
				//System.out.println(minTTC);
				
				trialLogger.setBrakeRT_success((int)reactionTime);
				
				reactionLogger.add(reactionGroupID, 1, reactionTime, startTime, relativeStartTime, comment);
				
				reactionTimer = null;
				comment = "";
				
				trialLogger.setReaction(1);
				trialLogger.writeLog();
				
				// play success sound
				AudioCenter.playSound(successSound);
				
				timerIsActive = false;
			}
			else if(failureReactionReported)
			{
				//System.out.println("FAILURE");
				//System.err.println("FAILED");
				trialLogger.setStartTime(startTime);
				
				float sumTTC=(float) TTC.get(0);
				float minTTC=(float) TTC.get(0);
				
				//System.out.println(sumTTC);
				//System.out.println(minTTC);
				//System.out.println(TTC.size());
				
				for (int a=1;a<=TTC.size()-1;a++)
				{
					sumTTC=sumTTC+(float)TTC.get(a);
					
					if (minTTC>(float)TTC.get(a))
					{
						minTTC=(float)TTC.get(a);
					}
					
				}
				
				avgTTC=sumTTC/TTC.size();
				trialLogger.setAvgTTC(avgTTC);
				trialLogger.setMinTTC(minTTC);
				
				//if(minTTC<=0.0715)
					//trialLogger.setNoCollisions(1);
				
				//System.out.println(avgTTC);
				//System.out.println(minTTC);
				
				reactionLogger.add(reactionGroupID, -1, reactionTime, startTime, relativeStartTime, comment);

				reactionTimer = null;
				comment = "";
				
				trialLogger.setReaction(0);
				trialLogger.writeLog();
				
				// play fail sound
				AudioCenter.playSound(failSound);
				
				timerIsActive = false;
			}
			//else
				//System.out.println("MISSED2");
		}
		
		if(soundTimerIsActive)
		{
			if(timeExceeded() || distanceExceeded())
			{
				AudioCenter.playSound(failSound);
				soundTimerIsActive = false;
			}
		}
	}
	

	private boolean hasChangedLanesWithoutPermission()
	{
		if(allowLaneChange)
			return false;
		else
			return hasChangedLanes();	
	}
	
	
	private boolean hasChangedLanes() 
	{
		if(startLane == null)
			return (getCurrentLane() != null);
		else
			return (!startLane.equals(getCurrentLane()));
	}
	
	
	private boolean distanceExceeded()
	{
		// if task completion distance is 0 or less --> no distance limit
		if(taskCompletionDistance<=0)
			return false;
		else
		{
			Vector3f currentPosition = sim.getCar().getPosition();
			//System.err.println("Distance: " + currentPosition.distance(startPosition));
			return (currentPosition.distance(startPosition) > taskCompletionDistance);
		}
	}


	private boolean timeExceeded()
	{
		// if task completion time is 0 or less --> no time limit
		if(taskCompletionTime<=0)
			return false;
		else
		{
			long currentTime = System.currentTimeMillis();
			//System.err.println("Time: " + (currentTime-startTime));
			return (currentTime-startTime > taskCompletionTime);
		}
	}


	private String getCurrentLane()
	{
		float currentX = sim.getCar().getPosition().getX();
				
		Map<String, LaneLimit> laneList = Simulator.getDrivingTask().getScenarioLoader().getLaneList();
		Iterator<Entry<String, LaneLimit>> it = laneList.entrySet().iterator();
	    while(it.hasNext()) 
	    {
	        Entry<String, LaneLimit> pairs = (Entry<String, LaneLimit>)it.next();
	        String laneID = pairs.getKey();
	        LaneLimit laneLimit = pairs.getValue();
	     
	        if(currentX >= laneLimit.getXMin() && currentX <= laneLimit.getXMax())
	        	return laneID;
	    }
	    
		return null;
	}

	private Vector3f getVehicleLoc(String obstacleName)
	{
		//System.out.println(obstacleName+ "hi");
		for(TrafficObject trafficObject : PhysicalTraffic.getTrafficObjectList())
		{
			if(trafficObject.getName().equals(obstacleName))
			{
				//System.out.println("Found");
				//System.out.println(trafficObject.getPosition());
				return trafficObject.getPosition();
			}
		}
		
/*		Spatial object = Util.findNode(sim.getRootNode(), obstacleName);
		System.out.println(object.getLocalTranslation());
		return object.getLocalTranslation();*/
		return null;
	}
	
	private Vector3f getObstacleLoc(String obstacleName)
	{
		//System.out.println(obstacleName+ "hi");
		/*for(TrafficObject trafficObject : PhysicalTraffic.getTrafficObjectList())
		{
			if(trafficObject.getName().equals(obstacleName))
			{
				System.out.println("Found");
				System.out.println(trafficObject.getPosition());
				return trafficObject.getPosition();
			}
		}*/
		
		Spatial object = Util.findNode(sim.getRootNode(), obstacleName);
		//System.out.println(object.getLocalTranslation());
		return object.getLocalTranslation();
//		return null;
	}
	
	
	private Float getTTC(Vector3f obstaclePos)
	{
		//float distanceToObstacle = obstaclePos.distance(sim.getCar().getPosition());
		
		//float lateralDistance = sim.getCar().getLateralDistance(obstaclePos);
		float forwardDistance = (sim.getCar().getForwardDistance(obstaclePos))/1000;
		
		float TTC = forwardDistance/((sim.getCar().getCurrentSpeedKmh())/3600);
		
		return TTC;
	}
}

