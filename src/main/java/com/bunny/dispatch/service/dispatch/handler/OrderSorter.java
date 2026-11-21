package com.bunny.dispatch.service.dispatch.handler;

import com.bunny.dispatch.service.dispatch.handler.model.PlannedOrder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class OrderSorter {

	private static final Comparator<PlannedOrder> BY_PRIORITY_THEN_WEIGHT_THEN_ID =
			Comparator.comparingInt((PlannedOrder o) -> o.priority().rank())
					.thenComparing(PlannedOrder::packageWeight, Comparator.reverseOrder())
					.thenComparing(PlannedOrder::orderId);

	private OrderSorter() {
	}

	public static List<PlannedOrder> sorted(List<PlannedOrder> orders) {
		List<PlannedOrder> copy = new ArrayList<>(orders);
		copy.sort(BY_PRIORITY_THEN_WEIGHT_THEN_ID);
		return copy;
	}

}
