package org.matsim.contrib.sarp.scheduler;

import java.util.List;

import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.dvrp.MatsimVrpContext;
import org.matsim.contrib.dvrp.data.Vehicle;
import org.matsim.contrib.dvrp.router.VrpPathCalculator;
import org.matsim.contrib.dvrp.router.VrpPathWithTravelData;
import org.matsim.contrib.dvrp.schedule.DriveTask;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.Schedules;
import org.matsim.contrib.dvrp.schedule.Schedule.ScheduleStatus;
import org.matsim.contrib.dvrp.schedule.Task;
import org.matsim.contrib.sarp.data.AbstractRequest;
import org.matsim.contrib.sarp.schedule.TaxiDropoffDriveTask;
import org.matsim.contrib.sarp.schedule.TaxiDropoffStayTask;
import org.matsim.contrib.sarp.schedule.TaxiPickupStayTask;
import org.matsim.contrib.sarp.schedule.TaxiTask;
import org.matsim.contrib.sarp.schedule.TaxiTask.TaxiTaskType;
import org.matsim.contrib.sarp.schedule.TaxiWaitStayTask;

public class TaxiScheduler 
{
	private final MatsimVrpContext context;
    private final VrpPathCalculator calculator;
    private final TaxiSchedulerParams params;
    
    public TaxiScheduler(MatsimVrpContext context, 
    		VrpPathCalculator calculator, TaxiSchedulerParams params)
    {
    	this.context = context;
    	this.calculator = calculator;
    	this.params = params;
    	
    	for(Vehicle veh: context.getVrpData().getVehicles())
    	{
			Schedule<TaxiTask> schedule = TaxiSchedules.getSchedule(veh);
					
    		schedule.addTask(new TaxiWaitStayTask(veh.getT0(), veh.getT1(), veh.getStartLink()));
    		
    	}
    }
    
    public TaxiSchedulerParams getParams()
    {
    	return this.params;
    }
    
    /*
     * A vehicle is idle iff vehicle is executing last task and
     * this last task is WAIT_STAY
     */
    public boolean isIdle(Vehicle vehicle)
    {
        double currentTime = context.getTime();
        
        //if time window T1 exceeded
        if (currentTime >= vehicle.getT1())
            return false;

        //else
        
        Schedule<TaxiTask> schedule = TaxiSchedules.getSchedule(vehicle);
        if (schedule.getStatus() != ScheduleStatus.STARTED) {
            return false;
        }

        TaxiTask currentTask = schedule.getCurrentTask();

        
        return Schedules.isLastTask(currentTask)
                && currentTask.getTaxiTaskType() == TaxiTaskType.WAIT_STAY;
    }
    
    /*
     * Before a vehicle execute a next task, we should check and update
     * schedule (begin time and end time of all remaining tasks)
     */
    public void updateBeforeNextTask(Schedule<TaxiTask> schedule)
    {
    	//if schedule has not been started
    	if(schedule.getStatus() != ScheduleStatus.STARTED)
    		return;
    	
    	//else, update begin time and end time for each tasks of this schedule
    	//by first getting current time
    	double endTime = context.getTime();
    	TaxiTask currentTask = schedule.getCurrentTask();
    	//and then updating
    	updateCurrentAndPlannedTasks(schedule, endTime);
    	
    	//if we do not know destination of a schedule, it mean that 
    	//the last task in this schedule is PICKUP_STAY ???
    	if(!params.destinationKnown)
    	{
    		//currentTask is the last task ???
    		if(currentTask.getTaxiTaskType() == TaxiTaskType.PICKUP_STAY)
    		{
    			//add DropoffDriveTask and DropoffStayTask to this schedule
    			appendDropoffAfterPickup(schedule);
    			
    			//add WaitStayTask after dropoffStayTask
    			appendWaitAfterDropoff(schedule);
    			
    		}
    	}
    }
    
    /*
     * In this situation, the last task in schedule is PickupStayTask
     * so we need to complete this schedule by adding DropoffDriveTask and
     * DropoffStayTask to it.
     */
    public void appendDropoffAfterPickup(Schedule<TaxiTask> schedule)
    {
    	//in this situation, pickupStayTask is the last task in the schedule
    	TaxiPickupStayTask pickupStayTask = (TaxiPickupStayTask)Schedules.getLastTask(schedule);
    	//get request
    	AbstractRequest req = pickupStayTask.getRequest();
    	Link reqFromLink = req.getFromLink();
    	Link reqToLink = req.getToLink();
    	
    	double t3 = pickupStayTask.getEndTime();
    	
    	//get path of dropoffDriveTask
    	VrpPathWithTravelData path = calculator.calcPath(reqFromLink, reqToLink, t3);
    	//add dropoffDriveTask into schedule
    	schedule.addTask(new TaxiDropoffDriveTask(path, req));
    	
    	//and with dropoffStayTask
    	double t4 = path.getArrivalTime();
    	double t5 = t4 + params.dropoffDuration;
    	schedule.addTask(new TaxiDropoffStayTask(t4, t5, req));
    	
    }
    
    public void appendWaitAfterDropoff(Schedule<TaxiTask> schedule)
    {
    	TaxiDropoffStayTask dropoffStayTask = (TaxiDropoffStayTask)Schedules.getLastTask(schedule);
    	
    	// add wait time
    	double t5 = dropoffStayTask.getEndTime();
    	//each vehicle has a working time from t0 to t1 ?
    	double tEnd = Math.max(t5, schedule.getVehicle().getT1());
    	Link link = dropoffStayTask.getLink();
    	
    	schedule.addTask(new TaxiWaitStayTask(t5, tEnd, link));
    			
    }
    
    /*
     * when real execution of transportation is difference with planned schedule
     * (ex: real time) we need to update begin time and end time foreach tasks
     */
    public void updateCurrentAndPlannedTasks(Schedule<TaxiTask> schedule, double currentTaskNewEndTime)
    {
    	Task currentTask = schedule.getCurrentTask();
    	//if schedule is OK
    	if(currentTask.getEndTime() == currentTaskNewEndTime)
    		return;
    	// else, need to update end time for this task
    	currentTask.setEndTime(currentTaskNewEndTime);
    	// and begin and end time for next tasks of this schedule
    	
    	//get list of tasks
    	List<TaxiTask> tasks = schedule.getTasks();
    	//get index of next task of current task index (+1)
    	int nextTaskIdx = currentTask.getTaskIdx() + 1;
    	
    	double t = currentTaskNewEndTime;
    	
    	//for each task
    	for(int i = nextTaskIdx; i < tasks.size(); i++)
    	{
    		TaxiTask task = tasks.get(i);
    		
    		switch(task.getTaxiTaskType())
    		{
    		case WAIT_STAY:
    		{
    			if(i == tasks.size()-1)// is last task
    			{
    				task.setBeginTime(t);
    				if(task.getEndTime() < t)//happend if the pervious task is delayed
    					//do not remove this task, a Taxi schedule should end with WAIT Status
    					task.setEndTime(t);
    			}
    			else //is not last task, mean that there is some other tasks
    				//have been added at time submissionTime <= t
    			{
    				//get next task
    				TaxiTask nextTask = tasks.get(i + 1);
    				switch(nextTask.getTaxiTaskType())
    				{
    				case PICKUP_DRIVE:
    				case CRUISE_DRIVE:
    					double endTime = task.getEndTime();
    					//if this WAIT_STAY task end before t then
    					//we should remove this WAIT_STAY task.
    					if(endTime <= t)
    					{
    						schedule.removeTask(task);
    						i--;
    					}
    					else
    					{
    						task.setBeginTime(t);
    						t = endTime;
    					}
    					break;
    					
    					default:
    						throw new RuntimeException();
    				
    				}
    			}
    			break;
    		}
    		
    		case PICKUP_DRIVE:
    		case DROPOFF_DRIVE:
    		case CRUISE_DRIVE:
    		{
    			//can not be shortened/lengthen, therefore must be moved
    			// forward/backward
    			//so we need to set new begin time
    			task.setBeginTime(t);
    			//and re-calculate end time
    			VrpPathWithTravelData path = (VrpPathWithTravelData)((DriveTask)task).getPath();
    			t += path.getArrivalTime();
    			//and then set new end time
    			task.setEndTime(t);
    			
    			break;
    		}
    		
    		case PICKUP_STAY:
    		{
    			//t = taxi's arrival time = begin time
    			task.setBeginTime(t);
    			// calculate end time
    			double t0 = ((TaxiPickupStayTask)task).getRequest().getT0();
    			t = Math.max(t, t0) + params.pickupDuration;
    			task.setEndTime(t);
    			
    			break;
    		}
    		
    		case DROPOFF_STAY:
    		{
    			//can not be shortened/lengthen, 
    			//therefore must be moved forward/backward
    			task.setBeginTime(t);
    			//dropoff customer immediately when arriving destination
    			t += params.dropoffDuration;
    			task.setEndTime(t);
    			
    			break;
    		}
    			    		
    		default:
    			throw new IllegalStateException();
    		}
    	}
    	
    	
    	
    }

}