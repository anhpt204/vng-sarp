package org.matsim.contrib.ssarp.schedule;

import org.matsim.contrib.dvrp.schedule.StayTaskImpl;
import org.matsim.contrib.ssarp.schedule.TaxiTaskWithRequest;
import org.matsim.contrib.ssarp.data.AbstractRequest;
import org.matsim.contrib.ssarp.enums.RequestType;

public class TaxiDropoffStayTask extends StayTaskImpl
	implements TaxiTaskWithRequest
{
	AbstractRequest request;

	public TaxiDropoffStayTask(double beginTime, double endTime, 
			AbstractRequest request) 
	{
		super(beginTime, endTime, request.getToLink());
		// TODO Auto-generated constructor stub
		this.request = request;
		request.setDropoffStayTask(this);
	}

	@Override
	public TaxiTaskType getTaxiTaskType() 
	{
		if(request.getType() == RequestType.PEOPLE)
			return TaxiTaskType.PEOPLE_DROPOFF_STAY;
		else
			return TaxiTaskType.PARCEL_DROPOFF_STAY;
		
	}

	@Override
	public AbstractRequest getRequest() 
	{
		return request;
	}

	@Override
	public void removeFromRequest() 
	{
		this.request.setDropoffStayTask(null);
		
	}
	
    @Override
    protected String commonToString()
    {
        return "[" + getTaxiTaskType().name() + "]" + super.commonToString();
    }

}