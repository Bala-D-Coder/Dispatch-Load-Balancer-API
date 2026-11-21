package com.bunny.dispatch.service.dispatch.handler.model;

import java.util.List;

public record VehicleRoute(
		PlannedVehicle vehicle,
		List<AssignedStop> orderedStops,
		List<RouteLeg> legs,
		double totalDistanceKm
) {

	public int totalLoad() {
		return orderedStops.stream().mapToInt(s -> s.order().packageWeight()).sum();
	}

}
