package com.bunny.dispatch.dto.response;

import com.bunny.dispatch.entity.common.Priority;
import com.bunny.dispatch.entity.common.UnassignedReason;

public record UnassignedOrderDto(
		String orderId,
		int packageWeight,
		Priority priority,
		UnassignedReason reason,
		String reasonMessage
) {

	public static UnassignedOrderDto of(String orderId, int packageWeight, Priority priority, UnassignedReason reason) {
		return new UnassignedOrderDto(orderId, packageWeight, priority, reason, reason.defaultMessage());
	}

}
