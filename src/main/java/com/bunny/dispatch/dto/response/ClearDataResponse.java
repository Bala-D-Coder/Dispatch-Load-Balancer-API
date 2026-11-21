package com.bunny.dispatch.dto.response;

public record ClearDataResponse(
		String message,
		String status,
		long deletedOrders,
		long deletedVehicles
) {

	public static ClearDataResponse of(long deletedOrders, long deletedVehicles) {
		return new ClearDataResponse("Dispatch data cleared.", "success", deletedOrders, deletedVehicles);
	}

}
