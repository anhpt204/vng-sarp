/**
 * Project: taxi
 * Package: org.matsim.contrib.sarp.vehreqpath
 * Author: pta
 * Date: Nov 24, 2014
 */
package org.matsim.contrib.ssarp.vehreqpath;

import org.matsim.contrib.ssarp.vehreqpath.VehicleRequestsRoute;

/**
 *
 * interface for define difference methods for calculate cost
 * of a path
 */
public interface VehicleRequestPathCost
{
	double getCost(VehicleRequestsRoute route);

}