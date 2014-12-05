package org.matsim.contrib.ssarp;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.matsim.analysis.LegHistogram;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.contrib.dvrp.MatsimVrpContext;
import org.matsim.contrib.dvrp.MatsimVrpContextImpl;
import org.matsim.contrib.dvrp.data.Request;
import org.matsim.contrib.dvrp.data.VrpDataImpl;
import org.matsim.contrib.dvrp.passenger.BeforeSimulationTripPrebooker;
import org.matsim.contrib.dvrp.passenger.PassengerEngine;
import org.matsim.contrib.dvrp.router.LeastCostPathCalculatorWithCache;
import org.matsim.contrib.dvrp.router.TravelTimeCalculators;
import org.matsim.contrib.dvrp.router.VrpPathCalculator;
import org.matsim.contrib.dvrp.router.VrpPathCalculatorImpl;
import org.matsim.contrib.dvrp.run.VrpLauncherUtils;
import org.matsim.contrib.dvrp.run.VrpLauncherUtils.TravelTimeSource;
import org.matsim.contrib.dvrp.run.VrpPopulationUtils;
import org.matsim.contrib.dvrp.schedule.DriveTask;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.Task;
import org.matsim.contrib.dvrp.util.time.TimeDiscretizer;
import org.matsim.contrib.dvrp.vrpagent.VrpLegs;
import org.matsim.contrib.dvrp.vrpagent.VrpLegs.LegCreator;
import org.matsim.contrib.dynagent.run.DynAgentLauncherUtils;
import org.matsim.contrib.ssarp.LauncherParams;
import org.matsim.contrib.ssarp.RequestCreator;
import org.matsim.contrib.ssarp.TaxiActionCreator;
import org.matsim.contrib.ssarp.util.VrpUtilities;
import org.matsim.contrib.ssarp.data.AbstractRequest;
import org.matsim.contrib.ssarp.data.ElectricVehicleReader;
import org.matsim.contrib.ssarp.data.AbstractRequest.TaxiRequestStatus;
import org.matsim.contrib.ssarp.optimizer.TaxiOptimizer;
import org.matsim.contrib.ssarp.optimizer.TaxiOptimizerConfiguration;
import org.matsim.contrib.ssarp.optimizer.TaxiOptimizerConfiguration.Goal;
import org.matsim.contrib.ssarp.passenger.SSARPassengerEngine;
import org.matsim.contrib.ssarp.scheduler.TaxiScheduler;
import org.matsim.contrib.ssarp.scheduler.TaxiSchedulerParams;
import org.matsim.contrib.ssarp.util.*;
import org.matsim.contrib.ssarp.vehreqpath.VehicleRequestPathFinder;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.algorithms.EventWriter;
import org.matsim.core.events.algorithms.EventWriterXML;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.router.Dijkstra;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;
import org.matsim.vis.otfvis.OTFVisConfigGroup.ColoringScheme;


public class Launcher
{
	private LauncherParams params;
	private MatsimVrpContext context;
	private final Scenario scenario;
	
	private TravelTimeCalculator travelTimeCalculator;
	private LeastCostPathCalculatorWithCache routerWithCache;
	private VrpPathCalculator pathCalculator;
	
	public Launcher(LauncherParams params)
	{
		this.params = params;
		//initialize a scenario
		this.scenario = VrpLauncherUtils.initScenario(params.netFile, params.plansFile);
		
		Map<Id<Link>, ? extends Link> links = this.scenario.getNetwork().getLinks();
		
		//load requests
		
		if(params.taxiCustomersFile != null)
		{
			List<String> passangerIds = VrpUtilities.readTaxiCustomerIds(params.taxiCustomersFile);
			VrpPopulationUtils.convertLegModes(passangerIds, RequestCreator.MODE, scenario);
		}
		
	}
	
	private void initVrpPathCalculator()
	{
		TravelTime travelTime = travelTimeCalculator == null? 
				VrpLauncherUtils.initTravelTime(this.scenario, params.algorithmConfig.ttimeSource
						, params.eventsFile): travelTimeCalculator.getLinkTravelTimes();
				
		TravelDisutility travelDisutility = VrpLauncherUtils.initTravelDisutility(params.algorithmConfig.tdisSource,
				travelTime);
		
		LeastCostPathCalculator router = new Dijkstra(this.scenario.getNetwork(),
				travelDisutility, travelTime);
		
		TimeDiscretizer timeDiscretizer = (params.algorithmConfig.ttimeSource == TravelTimeSource.FREE_FLOW_SPEED && //
		        !scenario.getConfig().network().isTimeVariantNetwork()) ? //
		                TimeDiscretizer.CYCLIC_24_HOURS : //
		                TimeDiscretizer.CYCLIC_15_MIN;
		
        routerWithCache = new LeastCostPathCalculatorWithCache(router, timeDiscretizer);
        pathCalculator = new VrpPathCalculatorImpl(routerWithCache, travelTime, travelDisutility);

	}

	void clearVrpPathCalculator()
    {
        travelTimeCalculator = null;
        routerWithCache = null;
        pathCalculator = null;
    }
	
	VrpDataImpl initTaxiData(Scenario scenario, String taxiFileName)
	{
		VrpDataImpl taxiData = new VrpDataImpl();
		new ElectricVehicleReader(scenario, taxiData).parse(taxiFileName);
		
		return taxiData;
	}
	
	
	void run(boolean warmup) throws FileNotFoundException, IOException
	{
		MatsimVrpContextImpl contextImpl = new MatsimVrpContextImpl();
		this.context = contextImpl;
		
		//scenario: network + population
		contextImpl.setScenario(scenario);
		
		//vehicle data: vehicles + requests
		VrpDataImpl taxiData = initTaxiData(scenario, params.taxisFile);
		contextImpl.setVrpData(taxiData);
		
		//optimizer
		TaxiOptimizerConfiguration optimizerConfig = createOptimizerConfig();
		//create optimizer
		TaxiOptimizer optimizer = params.algorithmConfig.createTaxiOptimizer(optimizerConfig);
		
		
		QSim qsim = DynAgentLauncherUtils.initQSim(scenario);
		contextImpl.setMobsimTimer(qsim.getSimTimer());
		
		//add optimizer to be a listener in simulation 
		qsim.addQueueSimulationListeners(optimizer);
		
		//PassengerEngine passengerEngine = VrpLauncherUtils.initPassengerEngine(RequestCreator.MODE, new RequestCreator(), 
		//		optimizer, contextImpl, qsim);
		//init SSARPassengerEnggine
		int timeStep = 5 * 60; //each 15 minutes
		SSARPassengerEngine passengerEngine = new SSARPassengerEngine(RequestCreator.MODE,
				new RequestCreator(), optimizer, contextImpl, 
				VrpUtilities.getRequestEntry(params.taxiCustomersFile, contextImpl), 
				qsim, timeStep);
		qsim.addMobsimEngine(passengerEngine);
		qsim.addDepartureHandler(passengerEngine);
		
		if (params.advanceRequestSubmission) {
            // yy to my ears, this is not completely clear.  I don't think that it enables advance request submission
            // for arbitrary times, but rather requests all trips before the simulation starts.  Doesn't it?  kai, jul'14

            //Yes. For a fully-featured advanced request submission process, use TripPrebookingManager, michalm, sept'14
            qsim.addQueueSimulationListeners(new BeforeSimulationTripPrebooker(passengerEngine));
        }

        LegCreator legCreator = params.onlineVehicleTracker ? VrpLegs
                .createLegWithOnlineTrackerCreator(optimizer, qsim.getSimTimer())
                : VrpLegs.LEG_WITH_OFFLINE_TRACKER_CREATOR;

        TaxiActionCreator actionCreator = new TaxiActionCreator(passengerEngine, legCreator,
                params.pickupDuration);

        VrpLauncherUtils.initAgentSources(qsim, context, optimizer, actionCreator);

        EventsManager events = qsim.getEventsManager();

        EventWriter eventWriter = null;
        if (params.eventsOutFile != null) {
            eventWriter = new EventWriterXML(params.eventsOutFile);
            events.addHandler(eventWriter);
        }

        if (warmup) {
            if (travelTimeCalculator == null) {
                travelTimeCalculator = TravelTimeCalculators.createTravelTimeCalculator(scenario);
            }

            events.addHandler(travelTimeCalculator);
        }
        //else {
        //    optimizerConfig.scheduler.setDelaySpeedupStats(delaySpeedupStats);
        //}

        //MovingAgentsRegister mar = new MovingAgentsRegister();
        //events.addHandler(mar);

        if (params.otfVis) { // OFTVis visualization
            DynAgentLauncherUtils.runOTFVis(qsim, true, ColoringScheme.standard);
        }

        //if (params.histogramOutDir != null) {
        //    events.addHandler(legHistogram = new LegHistogram(300));
        //}

        qsim.run();

        events.finishProcessing();

        if (params.eventsOutFile != null) {
            eventWriter.closeFile();
        }

        // check if all reqs have been served
        for (Request r : taxiData.getRequests()) 
        {
        	AbstractRequest request = (AbstractRequest)r;
            if (request.getStatus() != TaxiRequestStatus.PERFORMED) {
                throw new IllegalStateException();
            }
        }

        //if (cacheStats != null) {
        //    cacheStats.updateStats(routerWithCache);
        //}
		
	}
	private TaxiOptimizerConfiguration createOptimizerConfig() 
	{
		TaxiSchedulerParams schedulerParams = new TaxiSchedulerParams(this.params.destinationKnown,
				this.params.pickupDuration, this.params.dropoffDuration);
		
		TaxiScheduler scheduler = new TaxiScheduler(context, pathCalculator, schedulerParams);
		
		VehicleRequestPathFinder vrpFinder = new VehicleRequestPathFinder(pathCalculator, scheduler);
		
		return new TaxiOptimizerConfiguration(this.context, scheduler, Goal.MIN_PICKUP_TIME, this.params, vrpFinder);
	}
	
	private void generateOutput()
	{
		PopulationWriter popWriter = new PopulationWriter(this.scenario.getPopulation(), this.scenario.getNetwork());
		
		popWriter.write("./input/plans.vn.out.xml.gz");
	}

	/**
	 * @param args
	 * @throws IOException 
	 * @throws FileNotFoundException 
	 */
	public static void main(String[] args) throws FileNotFoundException, IOException
	{
		String paramsFile = "./input/grid/params.in";
		
//		String paramsFile = "./input/mielec/params.in";
    	//String paramsFile = "./input/params.in";
        LauncherParams params = LauncherParams.readParams(paramsFile);
        Launcher launcher = new Launcher(params);
        launcher.initVrpPathCalculator();
        launcher.run(false);
        launcher.generateOutput();
	}

	

}