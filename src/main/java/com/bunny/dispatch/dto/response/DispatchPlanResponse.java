package com.bunny.dispatch.dto.response;

import java.util.List;

public record DispatchPlanResponse(
		List<VehiclePlanDto> dispatchPlan,
		List<UnassignedOrderDto> unassignedOrders,
		PlanTotalsDto totals
) {
}
