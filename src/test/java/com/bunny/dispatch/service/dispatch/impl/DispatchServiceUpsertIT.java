package com.bunny.dispatch.service.dispatch.impl;

import com.bunny.dispatch.config.DispatchConfig;
import com.bunny.dispatch.dto.request.OrderRequest;
import com.bunny.dispatch.dto.request.OrdersBatchRequest;
import com.bunny.dispatch.dto.request.VehicleRequest;
import com.bunny.dispatch.dto.request.VehiclesBatchRequest;
import com.bunny.dispatch.dto.response.AckResponse;
import com.bunny.dispatch.dto.response.DispatchPlanResponse;
import com.bunny.dispatch.dto.response.VehiclePlanDto;
import com.bunny.dispatch.entity.OrderEntity;
import com.bunny.dispatch.entity.VehicleEntity;
import com.bunny.dispatch.entity.common.Priority;
import com.bunny.dispatch.mapper.DispatchMapperImpl;
import com.bunny.dispatch.repository.OrderRepository;
import com.bunny.dispatch.repository.VehicleRepository;
import com.bunny.dispatch.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withinPercentage;

/**
 * Real Postgres (Testcontainers) + real repositories + real algorithm through the service.
 * Tests 1 and 3 are the permanent fences for the merge-nulls pitfall on each side of the API:
 * an upsert must never erase createdAt. Round-trip goldens measured 2026-11-20 (Node + jshell,
 * identical): VEH001 10.120580 km, VEH002 7.936633 km, and 580.670438 km for the VEH002 route
 * that carries both stops once VEH001's capacity is shrunk below demand.
 */
@DataJpaTest
@Import({TestcontainersConfiguration.class, DispatchServiceImpl.class, DispatchMapperImpl.class, DispatchConfig.class})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class DispatchServiceUpsertIT {

	@Autowired
	private DispatchServiceImpl service;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private VehicleRepository vehicleRepository;

	@Autowired
	private EntityManager entityManager;

	private static OrdersBatchRequest orders(List<OrderRequest> list) {
		return new OrdersBatchRequest(list);
	}

	private static VehiclesBatchRequest vehicles(List<VehicleRequest> list) {
		return new VehiclesBatchRequest(list);
	}

	private static OrderRequest blrOrder(String id, int weight) {
		if ("ORD001".equals(id)) {
			return new OrderRequest(id, 12.9716, 77.5946, "MG Road, Bangalore, Karnataka, India", weight, Priority.HIGH);
		}
		return new OrderRequest(id, 13.0827, 80.2707, "Anna Salai, Chennai, Tamil Nadu, India", weight, Priority.MEDIUM);
	}

	private static VehiclesBatchRequest blrVehicles() {
		return vehicles(List.of(
				new VehicleRequest("VEH001", 100, 12.9716, 77.6413, "Indiranagar, Bangalore, Karnataka, India"),
				new VehicleRequest("VEH002", 150, 13.0674, 80.2376, "T Nagar, Chennai, Tamil Nadu, India")));
	}

	@Test
	void reingestPreservesCreatedAtReplacesWeight() {
		AckResponse first = service.ingestOrders(orders(List.of(
				blrOrder("ORD001", 15), blrOrder("ORD002", 20))));
		assertThat(first.inserted()).isEqualTo(2);
		assertThat(first.updated()).isZero();
		entityManager.flush();

		OrderEntity stored = orderRepository.findById("ORD001").orElseThrow();
		LocalDateTime createdAt = stored.getCreatedAt();
		assertThat(createdAt).isNotNull();

		AckResponse second = service.ingestOrders(orders(List.of(blrOrder("ORD001", 99))));
		assertThat(second.received()).isEqualTo(1);
		assertThat(second.inserted()).isZero();
		assertThat(second.updated()).isEqualTo(1);
		entityManager.flush();
		entityManager.clear();

		assertThat(orderRepository.count()).isEqualTo(2);
		OrderEntity reloaded = orderRepository.findById("ORD001").orElseThrow();
		assertThat(reloaded.getPackageWeight()).isEqualTo(99);
		assertThat(reloaded.getAddress()).isEqualTo("MG Road, Bangalore, Karnataka, India");
		assertThat(reloaded.getCreatedAt()).isNotNull();
		// DB TIMESTAMP is microsecond-precision; compare at second granularity — the point of
		// the fence is that createdAt was NOT nulled or replaced, not sub-second drift.
		assertThat(Duration.between(createdAt.truncatedTo(ChronoUnit.SECONDS),
				reloaded.getCreatedAt().truncatedTo(ChronoUnit.SECONDS)).abs().toSeconds())
				.isZero();
	}

	@Test
	void buildPlanThroughRealRepos_matchesBangaloreGoldens() {
		service.ingestVehicles(blrVehicles());
		service.ingestOrders(orders(List.of(blrOrder("ORD001", 10), blrOrder("ORD002", 20))));
		entityManager.flush();

		DispatchPlanResponse response = service.buildPlan();

		assertThat(response.dispatchPlan()).hasSize(2);
		assertThat(response.dispatchPlan().get(0).vehicleId()).isEqualTo("VEH001");
		assertThat(response.dispatchPlan().get(0).totalDistanceKm()).isCloseTo(10.120580, withinPercentage(0.5));
		assertThat(response.dispatchPlan().get(0).totalDistance()).isEqualTo("10 km");
		assertThat(response.dispatchPlan().get(1).vehicleId()).isEqualTo("VEH002");
		assertThat(response.dispatchPlan().get(1).totalDistanceKm()).isCloseTo(7.936633, withinPercentage(0.5));
		assertThat(response.totals().totalLoadKg()).isEqualTo(30);

		// upsert visible in the very next plan — fresh recompute, no cache (D6)
		service.ingestOrders(orders(List.of(blrOrder("ORD001", 25))));
		entityManager.flush();
		DispatchPlanResponse updated = service.buildPlan();
		assertThat(updated.totals().totalLoadKg()).isEqualTo(45);
	}

	@Test
	void reingestVehiclePreservesCreatedAtAndCapacityFeedsNextPlan() {
		service.ingestVehicles(blrVehicles());
		service.ingestOrders(orders(List.of(blrOrder("ORD001", 10), blrOrder("ORD002", 20))));
		entityManager.flush();

		VehicleEntity stored = vehicleRepository.findById("VEH001").orElseThrow();
		LocalDateTime createdAt = stored.getCreatedAt();
		assertThat(createdAt).isNotNull();
		assertThat(stored.getCapacity()).isEqualTo(100);

		// same point, so capacity is the only variable that can move the plan
		AckResponse second = service.ingestVehicles(vehicles(List.of(
				new VehicleRequest("VEH001", 5, 12.9716, 77.6413, "Jayanagar, Bangalore, Karnataka, India"))));
		assertThat(second.received()).isEqualTo(1);
		assertThat(second.inserted()).isZero();
		assertThat(second.updated()).isEqualTo(1);
		entityManager.flush();
		entityManager.clear();

		assertThat(vehicleRepository.count()).isEqualTo(2);
		VehicleEntity reloaded = vehicleRepository.findById("VEH001").orElseThrow();
		assertThat(reloaded.getCapacity()).isEqualTo(5);
		assertThat(reloaded.getCurrentAddress()).isEqualTo("Jayanagar, Bangalore, Karnataka, India");
		assertThat(reloaded.getCurrentPoint().getLatitude()).isEqualTo(12.9716);
		assertThat(Duration.between(createdAt.truncatedTo(ChronoUnit.SECONDS),
				reloaded.getCreatedAt().truncatedTo(ChronoUnit.SECONDS)).abs().toSeconds())
				.isZero();

		// VEH001 can no longer carry a 10 kg stop, so it drops out of the plan entirely and VEH002
		// takes both orders. RoutePlanner re-orders stops by nearest neighbour from the start, so
		// Anna Salai (3.97 km) is visited before MG Road (286.53 km) even though the HIGH-priority
		// order sits there: assignment order is not visit order.
		DispatchPlanResponse response = service.buildPlan();
		assertThat(response.dispatchPlan()).hasSize(1);
		VehiclePlanDto veh002 = response.dispatchPlan().get(0);
		assertThat(veh002.vehicleId()).isEqualTo("VEH002");
		assertThat(veh002.assignedOrders()).extracting(o -> o.orderId())
				.containsExactly("ORD002", "ORD001");
		assertThat(veh002.totalLoad()).isEqualTo(30);
		assertThat(veh002.legs()).hasSize(3);
		assertThat(veh002.legs().get(0).fromAddress()).isEqualTo("T Nagar, Chennai, Tamil Nadu, India");
		assertThat(veh002.legs().get(0).toAddress()).isEqualTo("Anna Salai, Chennai, Tamil Nadu, India");
		assertThat(veh002.legs().get(2).toAddress()).isEqualTo("T Nagar, Chennai, Tamil Nadu, India");
		assertThat(veh002.totalDistanceKm()).isCloseTo(580.670438, withinPercentage(0.5));
		assertThat(veh002.totalDistance()).isEqualTo("581 km");
		assertThat(response.unassignedOrders()).isEmpty();
		assertThat(response.totals().totalDistance()).isEqualTo("581 km");
	}

}
