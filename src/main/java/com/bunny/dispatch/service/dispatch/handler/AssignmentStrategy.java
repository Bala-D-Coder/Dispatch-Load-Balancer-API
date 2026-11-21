package com.bunny.dispatch.service.dispatch.handler;

import com.bunny.dispatch.service.dispatch.handler.model.AssignmentResult;
import com.bunny.dispatch.service.dispatch.handler.model.PlannedOrder;
import com.bunny.dispatch.service.dispatch.handler.model.PlannedVehicle;

import java.util.List;

/**
 * Strategy pattern seam for the dispatch algorithm. {@link GreedyAssignmentStrategy} is the
 * shipped implementation; a savings-algorithm variant (e.g. Clarke-Wright) can be added here
 * without touching the service or controller layers.
 */
public interface AssignmentStrategy {

	AssignmentResult assign(List<PlannedOrder> orders, List<PlannedVehicle> vehicles);

}
