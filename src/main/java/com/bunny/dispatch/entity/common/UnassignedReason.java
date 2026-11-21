package com.bunny.dispatch.entity.common;

public enum UnassignedReason {

	EXCEEDS_ALL_VEHICLE_CAPACITIES("Order weight exceeds every vehicle's total capacity"),
	NO_VEHICLE_WITH_SUFFICIENT_REMAINING_CAPACITY("No vehicle has enough remaining capacity"),
	NO_VEHICLES_AVAILABLE("No vehicles registered");

	private final String defaultMessage;

	UnassignedReason(String defaultMessage) {
		this.defaultMessage = defaultMessage;
	}

	public String defaultMessage() {
		return defaultMessage;
	}

}
