package com.bunny.dispatch.service.dispatch.handler.model;

import com.bunny.dispatch.entity.common.Priority;

public record PlannedOrder(
		String orderId,
		double latitude,
		double longitude,
		String address,
		int packageWeight,
		Priority priority
) {
}
