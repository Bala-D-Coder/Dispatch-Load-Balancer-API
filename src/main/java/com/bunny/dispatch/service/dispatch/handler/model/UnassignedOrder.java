package com.bunny.dispatch.service.dispatch.handler.model;

import com.bunny.dispatch.entity.common.UnassignedReason;

public record UnassignedOrder(
		PlannedOrder order,
		UnassignedReason reason
) {
}
