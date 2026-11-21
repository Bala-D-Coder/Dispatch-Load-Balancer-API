package com.bunny.dispatch.service.dispatch.handler.model;

public record PlannedVehicle(
		String vehicleId,
		int capacity,
		double currentLatitude,
		double currentLongitude,
		String currentAddress
) {
}
