package com.bunny.dispatch.dto.response;

import java.util.List;

public record VehiclePlanDto(
		String vehicleId,
		String currentAddress,
		int totalLoad,
		String totalDistance,
		double totalDistanceKm,
		List<RouteLegDto> legs,
		List<AssignedOrderDto> assignedOrders
) {

	public static String formatKm(double km) {
		return Math.round(km) + " km";
	}

}
