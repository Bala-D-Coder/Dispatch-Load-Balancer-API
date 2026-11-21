package com.bunny.dispatch.service.dispatch.handler;

import com.bunny.dispatch.entity.common.Priority;
import com.bunny.dispatch.service.dispatch.handler.model.PlannedOrder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class OrderSorterTest {

	private static PlannedOrder order(String id, int weight, Priority priority) {
		return new PlannedOrder(id, 28.6139, 77.2090, "CP", weight, priority);
	}

	private static final List<PlannedOrder> FIXTURE = List.of(
			order("ORD006", 40, Priority.LOW),
			order("ORD001", 15, Priority.HIGH),
			order("ORD021", 25, Priority.MEDIUM),
			order("ORD020", 30, Priority.HIGH),
			order("ORD002", 10, Priority.MEDIUM),
			order("ORD018", 10, Priority.HIGH)
	);

	@Test
	void ordersSortByPriorityThenWeightThenId() {
		List<String> ids = OrderSorter.sorted(FIXTURE).stream().map(PlannedOrder::orderId).toList();
		// HIGH: 30, 15, 10 | MEDIUM: 25, 10 | LOW: 40
		assertThat(ids).containsExactly("ORD020", "ORD001", "ORD018", "ORD021", "ORD002", "ORD006");
	}

	@Test
	void shuffledInputProducesSameSequence() {
		List<PlannedOrder> mutable = new ArrayList<>(FIXTURE);
		Random random = new Random(42);
		String expected = OrderSorter.sorted(FIXTURE).toString();
		for (int i = 0; i < 5; i++) {
			Collections.shuffle(mutable, random);
			assertThat(OrderSorter.sorted(mutable).toString()).isEqualTo(expected);
		}
	}

	@Test
	void doesNotMutateInput() {
		List<PlannedOrder> mutable = new ArrayList<>(FIXTURE);
		OrderSorter.sorted(mutable);
		assertThat(mutable).isEqualTo(FIXTURE);
	}

	@Test
	void emptyAndSingleAreTrivial() {
		assertThat(OrderSorter.sorted(List.of())).isEmpty();
		assertThat(OrderSorter.sorted(List.of(order("X", 5, Priority.LOW))))
				.containsExactly(order("X", 5, Priority.LOW));
	}

}
