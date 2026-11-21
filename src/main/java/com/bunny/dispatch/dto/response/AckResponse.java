package com.bunny.dispatch.dto.response;

public record AckResponse(
		String message,
		String status,
		int received,
		int inserted,
		int updated
) {

	public static AckResponse orders(int received, int inserted, int updated) {
		return new AckResponse("Delivery orders accepted.", "success", received, inserted, updated);
	}

	public static AckResponse vehicles(int received, int inserted, int updated) {
		return new AckResponse("Vehicle details accepted.", "success", received, inserted, updated);
	}

}
