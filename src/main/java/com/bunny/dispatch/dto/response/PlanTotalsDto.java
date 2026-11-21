package com.bunny.dispatch.dto.response;

public record PlanTotalsDto(
		int assignedOrderCount,
		int unassignedOrderCount,
		int totalLoadKg,
		double totalDistanceKm,
		String totalDistance
) {
}
