package com.bunny.dispatch.dto.response;

public record RouteLegDto(
		String fromAddress,
		String toAddress,
		double distanceKm
) {
}
