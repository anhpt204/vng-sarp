package org.matsim.contrib.sarp.schedule;

import org.matsim.contrib.dvrp.router.VrpPathWithTravelData;
import org.matsim.contrib.dvrp.schedule.DriveTaskImpl;
import org.matsim.contrib.sarp.data.AbstractRequest;

public class TaxiDropoffDriveTask extends DriveTaskImpl
	implements TaxiTaskWithRequest
{
	private AbstractRequest request;
	
	public TaxiDropoffDriveTask(VrpPathWithTravelData path, AbstractRequest request)
	{
		super(path);
		// TODO Auto-generated constructor stub
		if(request.getFromLink() != path.getFromLink() 
				&& request.getToLink() != path.getToLink())
		{
			throw new IllegalArgumentException();
		}
		this.request = request;
		request.setDropoffDriveTask(this);
	}

	@Override
	public TaxiTaskType getTaxiTaskType() {
		// TODO Auto-generated method stub
		return TaxiTaskType.DROPOFF_DRIVE;
	}

	@Override
	public AbstractRequest getRequest() {
		// TODO Auto-generated method stub
		return this.request;
	}

	@Override
	public void removeFromRequest() {
		// TODO Auto-generated method stub
		this.request.setDropoffDriveTask(null);
		
	}
	
    @Override
    protected String commonToString()
    {
        return "[" + getTaxiTaskType().name() + "]" + super.commonToString();
    }

}