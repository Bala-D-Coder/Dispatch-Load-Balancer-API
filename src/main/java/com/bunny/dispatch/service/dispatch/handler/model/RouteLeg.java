package com.bunny.dispatch.service.dispatch.handler.model;

public record RouteLeg(
		String fromAddress,
		String toAddress,
		double distanceKm
) {
}
