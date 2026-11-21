package com.bunny.dispatch.dto.response;

import com.bunny.dispatch.entity.common.Priority;

public record AssignedOrderDto(
		String orderId,
		double latitude,
		double longitude,
		String address,
		int packageWeight,
		Priority priority
) {
}
