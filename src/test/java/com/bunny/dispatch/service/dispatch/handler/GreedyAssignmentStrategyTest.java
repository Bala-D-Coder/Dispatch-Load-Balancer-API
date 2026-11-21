package com.bunny.dispatch.service.dispatch.handler;

import com.bunny.dispatch.entity.common.Priority;
import com.bunny.dispatch.entity.common.UnassignedReason;
import com.bunny.dispatch.service.dispatch.handler.model.AssignmentResult;
import com.bunny.dispatch.service.dispatch.handler.model.PlannedOrder;
import com.bunny.dispatch.service.dispatch.handler.model.PlannedVehicle;
import com.bunny.dispatch.service.dispatch.handler.model.UnassignedOrder;
import com.bunny.dispatch.service.dispatch.handler.model.VehicleRoute;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withinPercentage;

class GreedyAssignmentStrategyTest {

	private static final PlannedVehicle CP_VEHICLE_20 =
			new PlannedVehicle("VEH-A", 20, DispatchFixtures.CP_LAT, DispatchFixtures.CP_LON, "Depot");
	private static final PlannedVehicle CP_VEHICLE_100 =
			new PlannedVehicle("VEH-A", 100, DispatchFixtures.CP_LAT, DispatchFixtures.CP_LON, "Connaught Place, Delhi, India");

	private final GreedyAssignmentStrategy strategy = new GreedyAssignmentStrategy();

	@Test
	void exactFitIsAllowed() {
		PlannedOrder order = DispatchFixtures.order("ORD001",
				DispatchFixtures.CP_LAT, DispatchFixtures.CP_LON, "Depot", 20, Priority.HIGH);
		AssignmentResult result = strategy.assign(List.of(order), List.of(CP_VEHICLE_20));
		assertThat(result.unassigned()).isEmpty();
		assertThat(result.routes()).hasSize(1);
		assertThat(result.routes().get(0).totalLoad()).isEqualTo(20);
	}

	@Test
	void oneKgOverIsUnassigned() {
		PlannedOrder order = DispatchFixtures.order("ORD001",
				DispatchFixtures.CP_LAT, DispatchFixtures.CP_LON, "Depot", 21, Priority.HIGH);
		AssignmentResult result = strategy.assign(List.of(order), List.of(CP_VEHICLE_20));
		assertThat(result.routes()).isEmpty();
		assertThat(result.unassigned()).singleElement()
				.extracting(UnassignedOrder::reason)
				.isEqualTo(UnassignedReason.EXCEEDS_ALL_VEHICLE_CAPACITIES);
	}

	@Test
	void highPriorityWinsTightSlotEvenIfFarther() {
		PlannedOrder lowNear = DispatchFixtures.order("ORD001",
				DispatchFixtures.CP_LAT, DispatchFixtures.CP_LON, "Depot", 15, Priority.LOW);
		PlannedOrder highFar = DispatchFixtures.order("ORD002",
				DispatchFixtures.KB_LAT, DispatchFixtures.KB_LON, "Karol Bagh", 10, Priority.HIGH);
		AssignmentResult result = strategy.assign(List.of(lowNear, highFar), List.of(CP_VEHICLE_20));

		Set<String> assignedIds = result.routes().stream()
				.flatMap(r -> r.orderedStops().stream())
				.map(s -> s.order().orderId())
				.collect(Collectors.toSet());
		assertThat(assignedIds).containsExactly("ORD002");
		assertThat(result.unassigned()).singleElement()
				.satisfies(u -> {
					assertThat(u.order().orderId()).isEqualTo("ORD001");
					assertThat(u.reason()).isEqualTo(UnassignedReason.NO_VEHICLE_WITH_SUFFICIENT_REMAINING_CAPACITY);
				});
	}

	@Test
	void nearestFeasibleVehicleChosen() {
		PlannedOrder karolBaghOrder = DispatchFixtures.order("ORD001",
				DispatchFixtures.KB_LAT, DispatchFixtures.KB_LON, "Karol Bagh, Delhi, India", 10, Priority.HIGH);
		PlannedVehicle chennaiFar = new PlannedVehicle("VEH-01", 100, 13.0827, 80.2707, "Anna Salai, Chennai");
		PlannedVehicle cpNear = new PlannedVehicle("VEH-02", 100,
				DispatchFixtures.CP_LAT, DispatchFixtures.CP_LON, "Connaught Place, Delhi, India");

		AssignmentResult result = strategy.assign(List.of(karolBaghOrder), List.of(chennaiFar, cpNear));

		assertThat(result.routes()).singleElement()
				.satisfies(route -> {
					assertThat(route.vehicle().vehicleId()).isEqualTo("VEH-02");
					// Part 1 golden: CP -> Karol Bagh
					assertThat(route.orderedStops()).singleElement()
							.satisfies(s -> assertThat(s.leg().distanceKm())
									.isCloseTo(14.442261, withinPercentage(0.5)));
				});
	}

	@Test
	void oversizedOrderVsFleet() {
		PlannedOrder heavy = DispatchFixtures.order("ORD001",
				DispatchFixtures.CP_LAT, DispatchFixtures.CP_LON, "Depot", 500, Priority.HIGH);
		PlannedVehicle v1 = new PlannedVehicle("VEH-A", 120,
				DispatchFixtures.CP_LAT, DispatchFixtures.CP_LON, "Depot");
		PlannedVehicle v2 = new PlannedVehicle("VEH-B", 100,
				DispatchFixtures.KB_LAT, DispatchFixtures.KB_LON, "Depot");
		AssignmentResult result = strategy.assign(List.of(heavy), List.of(v1, v2));
		assertThat(result.unassigned()).singleElement()
				.extracting(UnassignedOrder::reason)
				.isEqualTo(UnassignedReason.EXCEEDS_ALL_VEHICLE_CAPACITIES);
	}

	@Test
	void emptyOrdersProduceEmptyResult() {
		AssignmentResult result = strategy.assign(List.of(), List.of(CP_VEHICLE_20));
		assertThat(result.routes()).isEmpty();
		assertThat(result.unassigned()).isEmpty();
	}

	@Test
	void emptyVehiclesLeaveAllOrdersUnassigned() {
		PlannedOrder order = DispatchFixtures.order("ORD001",
				DispatchFixtures.CP_LAT, DispatchFixtures.CP_LON, "Depot", 5, Priority.LOW);
		AssignmentResult result = strategy.assign(List.of(order), List.of());
		assertThat(result.routes()).isEmpty();
		assertThat(result.unassigned()).singleElement()
				.extracting(UnassignedOrder::reason)
				.isEqualTo(UnassignedReason.NO_VEHICLES_AVAILABLE);
	}

	@Test
	void bothEmptyIsFine() {
		AssignmentResult result = strategy.assign(List.of(), List.of());
		assertThat(result.routes()).isEmpty();
		assertThat(result.unassigned()).isEmpty();
	}

	@Test
	void identicalCoordinatesZeroLegs() {
		List<PlannedOrder> orders = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			orders.add(DispatchFixtures.order("ORD%03d".formatted(i),
					DispatchFixtures.CP_LAT, DispatchFixtures.CP_LON, "Connaught Place, Delhi, India",
					10, Priority.MEDIUM));
		}
		AssignmentResult result = strategy.assign(orders, List.of(CP_VEHICLE_100));
		assertThat(result.routes()).singleElement()
				.satisfies(route -> {
					assertThat(route.orderedStops()).hasSize(5);
					route.orderedStops().forEach(s -> assertThat(s.leg().distanceKm()).isZero());
					route.legs().forEach(l -> assertThat(l.distanceKm()).isZero());
					assertThat(route.totalDistanceKm()).isZero();
				});
	}

	@Test
	void tieBreakPrefersMaxRemaining() {
		PlannedOrder order = DispatchFixtures.order("ORD001",
				DispatchFixtures.CP_LAT, DispatchFixtures.CP_LON, "Depot", 10, Priority.HIGH);
		PlannedVehicle small = new PlannedVehicle("VEH-A", 80,
				DispatchFixtures.CP_LAT, DispatchFixtures.CP_LON, "Depot");
		PlannedVehicle large = new PlannedVehicle("VEH-B", 120,
				DispatchFixtures.CP_LAT, DispatchFixtures.CP_LON, "Depot");
		AssignmentResult result = strategy.assign(List.of(order), List.of(small, large));
		assertThat(result.routes()).singleElement()
				.extracting(r -> r.vehicle().vehicleId())
				.isEqualTo("VEH-B");
	}

	@Test
	void tieBreakPrefersLowerVehicleIdWhenCapacityEqual() {
		PlannedOrder order = DispatchFixtures.order("ORD001",
				DispatchFixtures.CP_LAT, DispatchFixtures.CP_LON, "Depot", 10, Priority.HIGH);
		PlannedVehicle first = new PlannedVehicle("VEH-A", 100,
				DispatchFixtures.CP_LAT, DispatchFixtures.CP_LON, "Depot");
		PlannedVehicle second = new PlannedVehicle("VEH-B", 100,
				DispatchFixtures.CP_LAT, DispatchFixtures.CP_LON, "Depot");
		AssignmentResult result = strategy.assign(List.of(order), List.of(second, first));
		assertThat(result.routes()).singleElement()
				.extracting(r -> r.vehicle().vehicleId())
				.isEqualTo("VEH-A");
	}

	@Test
	void delhiOverflowInvariant() {
		// Measured 2026-11-20 (a throwaway Java preflight, cross-checked against Node.js
		// with identical results): 645 kg over 500 kg capacity -> all 12 HIGH (275 kg) and all
		// 8 MEDIUM (145 kg) assigned; exactly 7 LOW orders (150 kg) overflow. 145 kg is the
		// arithmetic floor; the extra 5 kg is stranded in VEH003 (115/120 used).
		AssignmentResult result = strategy.assign(
				DispatchFixtures.delhiOrders(), DispatchFixtures.delhiVehicles());

		Set<String> assignedIds = result.routes().stream()
				.flatMap(r -> r.orderedStops().stream())
				.map(s -> s.order().orderId())
				.collect(Collectors.toSet());
		// every HIGH and MEDIUM order is assigned (3 LOW orders also fit: ORD010, ORD014, ORD016)
		assertThat(assignedIds).containsAll(
				DispatchFixtures.delhiOrders().stream()
						.filter(o -> o.priority() != Priority.LOW)
						.map(PlannedOrder::orderId)
						.toList());

		assertThat(result.unassigned()).hasSize(7);
		assertThat(result.unassigned()).allSatisfy(u -> {
			assertThat(u.order().priority()).isEqualTo(Priority.LOW);
			assertThat(u.reason())
					.isEqualTo(UnassignedReason.NO_VEHICLE_WITH_SUFFICIENT_REMAINING_CAPACITY);
		});
		assertThat(result.unassigned().stream().map(u -> u.order().orderId()).toList())
				.containsExactlyInAnyOrder("ORD003", "ORD006", "ORD019", "ORD022",
						"ORD024", "ORD028", "ORD030");
		assertThat(result.unassigned().stream().mapToInt(u -> u.order().packageWeight()).sum())
				.isEqualTo(150);
	}

	@Test
	void determinismShuffledInput() {
		List<PlannedOrder> orders = DispatchFixtures.delhiOrders();
		List<PlannedVehicle> vehicles = DispatchFixtures.delhiVehicles();
		AssignmentResult expected = strategy.assign(orders, vehicles);

		Random random = new Random(7);
		for (int shuffle = 0; shuffle < 3; shuffle++) {
			List<PlannedOrder> shuffledOrders = new ArrayList<>(orders);
			Collections.shuffle(shuffledOrders, random);
			List<PlannedVehicle> shuffledVehicles = new ArrayList<>(vehicles);
			Collections.shuffle(shuffledVehicles, random);
			assertThat(strategy.assign(shuffledOrders, shuffledVehicles)).isEqualTo(expected);
		}
	}

	@Test
	void capacityNeverViolated_property() {
		List<DispatchFixtures.Dataset> datasets = DispatchFixtures.seededDatasets(42, 200);
		for (int i = 0; i < datasets.size(); i++) {
			DispatchFixtures.Dataset dataset = datasets.get(i);
			AssignmentResult result = strategy.assign(dataset.orders(), dataset.vehicles());
			String context = "seed=42 dataset=" + i;

			for (VehicleRoute route : result.routes()) {
				assertThat(route.totalLoad())
						.as(context + " vehicle " + route.vehicle().vehicleId())
						.isLessThanOrEqualTo(route.vehicle().capacity());
			}
			List<String> outcomeIds = new ArrayList<>();
			result.routes().forEach(r -> r.orderedStops().forEach(s -> outcomeIds.add(s.order().orderId())));
			result.unassigned().forEach(u -> outcomeIds.add(u.order().orderId()));
			assertThat(outcomeIds)
					.as(context + " every order assigned or unassigned exactly once")
					.containsExactlyInAnyOrderElementsOf(
							dataset.orders().stream().map(PlannedOrder::orderId).toList());
		}
	}

}
