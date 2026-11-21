package com.bunny.dispatch.service.dispatch.handler;

import com.bunny.dispatch.service.dispatch.handler.model.AssignedStop;
import com.bunny.dispatch.service.dispatch.handler.model.PlannedOrder;
import com.bunny.dispatch.service.dispatch.handler.model.PlannedVehicle;
import com.bunny.dispatch.service.dispatch.handler.model.RouteLeg;
import com.bunny.dispatch.service.dispatch.handler.model.VehicleRoute;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Re-orders the orders assigned to one vehicle by greedy nearest-neighbour and reports the
 * round trip (start -> every stop -> start), so {@code legs} always has stops.size() + 1
 * entries (zero stops -> empty route).
 *
 * <p>Note the deliberate asymmetry: greedy <em>assignment</em> ({@link GreedyAssignmentStrategy})
 * selects vehicles by forward-only tip distance, while the <em>reported</em> distance here is
 * the full round trip. Selection heuristic vs. reported plan — not a contradiction.
 */
public class RoutePlanner {

	private static final Comparator<PlannedOrder> BY_PRIORITY_THEN_WEIGHT_THEN_ID =
			Comparator
					.comparingInt((PlannedOrder o) -> o.priority().rank())
					.thenComparing(PlannedOrder::packageWeight, Comparator.reverseOrder())
					.thenComparing(PlannedOrder::orderId);

	public VehicleRoute plan(PlannedVehicle vehicle, List<PlannedOrder> assigned) {
		return plan(vehicle, assigned, new DistanceCache());
	}

	public VehicleRoute plan(PlannedVehicle vehicle, List<PlannedOrder> assigned, DistanceCache cache) {
		List<PlannedOrder> remaining = new ArrayList<>(assigned);
		remaining.sort(Comparator.comparing(PlannedOrder::orderId));

		double tipLat = vehicle.currentLatitude();
		double tipLon = vehicle.currentLongitude();
		String tipAddress = vehicle.currentAddress();

		List<AssignedStop> stops = new ArrayList<>();
		List<RouteLeg> legs = new ArrayList<>();

		while (!remaining.isEmpty()) {
			PlannedOrder best = null;
			double bestKm = 0.0;
			for (PlannedOrder candidate : remaining) {
				double km = cache.km(tipLat, tipLon, candidate.latitude(), candidate.longitude());
				if (best == null || km < bestKm
						|| (km == bestKm && BY_PRIORITY_THEN_WEIGHT_THEN_ID.compare(candidate, best) < 0)) {
					best = candidate;
					bestKm = km;
				}
			}
			RouteLeg leg = new RouteLeg(tipAddress, best.address(), bestKm);
			legs.add(leg);
			stops.add(new AssignedStop(best, leg));
			tipLat = best.latitude();
			tipLon = best.longitude();
			tipAddress = best.address();
			remaining.remove(best);
		}

		double total = 0.0;
		if (!stops.isEmpty()) {
			RouteLeg back = new RouteLeg(tipAddress, vehicle.currentAddress(),
					cache.km(tipLat, tipLon, vehicle.currentLatitude(), vehicle.currentLongitude()));
			legs.add(back);
		}
		for (RouteLeg leg : legs) {
			total += leg.distanceKm();
		}
		return new VehicleRoute(vehicle, List.copyOf(stops), List.copyOf(legs), total);
	}

}
