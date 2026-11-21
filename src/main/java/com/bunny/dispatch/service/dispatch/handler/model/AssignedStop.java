package com.bunny.dispatch.service.dispatch.handler.model;

public record AssignedStop(
		PlannedOrder order,
		RouteLeg leg
) {
}
