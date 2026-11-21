package com.bunny.dispatch.service.dispatch.handler.model;

import java.util.List;

public record AssignmentResult(
		List<VehicleRoute> routes,
		List<UnassignedOrder> unassigned
) {
}
