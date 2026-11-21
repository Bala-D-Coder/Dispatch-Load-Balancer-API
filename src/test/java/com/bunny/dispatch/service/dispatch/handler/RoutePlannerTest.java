package com.bunny.dispatch.service.dispatch.handler;

import com.bunny.dispatch.entity.common.Priority;
import com.bunny.dispatch.service.dispatch.handler.model.PlannedOrder;
import com.bunny.dispatch.service.dispatch.handler.model.PlannedVehicle;
import com.bunny.dispatch.service.dispatch.handler.model.RouteLeg;
import com.bunny.dispatch.service.dispatch.handler.model.VehicleRoute;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.withinPercentage;

/**
 * Leg goldens from the Part 4 preflight (Java one-shot, Node-cross-checked):
 * KB->Noida 33.829872, KB->Gurgaon 28.189927, Noida->Gurgaon 36.599016; plus Part 1
 * CP->KB 14.442261, CP->Noida 19.795386, CP->Gurgaon 24.743390.
 */
class RoutePlannerTest {

	private static final String CP = "Connaught Place, Delhi, India";
	private static final String KAROL_BAGH = "Karol Bagh, Delhi, India";
	private static final String NOIDA = "Sector 18, Noida, Uttar Pradesh, India";
	private static final String GURGAON = "Cyber Hub, Gurgaon, Haryana, India";

	private static final PlannedVehicle CP_VEHICLE = new PlannedVehicle("VEH-01", 500,
			DispatchFixtures.CP_LAT, DispatchFixtures.CP_LON, CP);

	private final RoutePlanner planner = new RoutePlanner();

	private static PlannedOrder kb() {
		return DispatchFixtures.order("ORD-KB", DispatchFixtures.KB_LAT,
				DispatchFixtures.KB_LON, KAROL_BAGH, 10, Priority.MEDIUM);
	}

	private static PlannedOrder noida() {
		return DispatchFixtures.order("ORD-NO", DispatchFixtures.NOIDA_LAT,
				DispatchFixtures.NOIDA_LON, NOIDA, 10, Priority.MEDIUM);
	}

	private static PlannedOrder gurgaon() {
		return DispatchFixtures.order("ORD-GU", DispatchFixtures.GURGAON_LAT,
				DispatchFixtures.GURGAON_LON, GURGAON, 10, Priority.MEDIUM);
	}

	@Test
	void singleStopIsRoundTrip() {
		VehicleRoute route = planner.plan(CP_VEHICLE, List.of(kb()));
		assertThat(route.legs()).hasSize(2);
		assertThat(route.legs().get(0).fromAddress()).isEqualTo(CP);
		assertThat(route.legs().get(0).toAddress()).isEqualTo(KAROL_BAGH);
		assertThat(route.legs().get(1).fromAddress()).isEqualTo(KAROL_BAGH);
		assertThat(route.legs().get(1).toAddress()).isEqualTo(CP);
		assertThat(route.totalDistanceKm()).isCloseTo(2 * 14.442261, withinPercentage(0.5));
	}

	@Test
	void nearestNeighbourOrderOnKnownLayout() {
		// expected: CP -> KB (14.44) -> Gurgaon (28.19) -> Noida (36.60) -> CP (19.80)
		VehicleRoute route = planner.plan(CP_VEHICLE, List.of(noida(), gurgaon(), kb()));
		assertThat(route.orderedStops().stream().map(s -> s.order().orderId()).toList())
				.containsExactly("ORD-KB", "ORD-GU", "ORD-NO");
		assertThat(route.totalDistanceKm())
				.isCloseTo(14.442261 + 28.189927 + 36.599016 + 19.795386, withinPercentage(0.5));
	}

	@Test
	void legsSumEqualsTotal() {
		GreedyAssignmentStrategy strategy = new GreedyAssignmentStrategy();
		List<DispatchFixtures.Dataset> datasets = DispatchFixtures.seededDatasets(42, 200);
		for (int i = 0; i < datasets.size(); i++) {
			for (VehicleRoute route : strategy.assign(datasets.get(i).orders(),
					datasets.get(i).vehicles()).routes()) {
				double sum = route.legs().stream().mapToDouble(RouteLeg::distanceKm).sum();
				assertThat(sum)
						.as("seed=42 dataset=" + i + " vehicle=" + route.vehicle().vehicleId())
						.isCloseTo(route.totalDistanceKm(), within(1e-9));
			}
		}
	}

	@Test
	void routeStartsAndEndsAtVehicleAddress() {
		List<DispatchFixtures.Dataset> datasets = DispatchFixtures.seededDatasets(42, 25);
		GreedyAssignmentStrategy strategy = new GreedyAssignmentStrategy();
		boolean sawRoute = false;
		for (DispatchFixtures.Dataset dataset : datasets) {
			for (VehicleRoute route : strategy.assign(dataset.orders(), dataset.vehicles()).routes()) {
				sawRoute = true;
				String depot = route.vehicle().currentAddress();
				assertThat(route.legs().get(0).fromAddress()).isEqualTo(depot);
				assertThat(route.legs().get(route.legs().size() - 1).toAddress()).isEqualTo(depot);
				assertThat(route.legs()).hasSize(route.orderedStops().size() + 1);
			}
		}
		assertThat(sawRoute).isTrue();
	}

	@Test
	void zeroStopsYieldsEmptyRoute() {
		VehicleRoute route = planner.plan(CP_VEHICLE, List.of());
		assertThat(route.legs()).isEmpty();
		assertThat(route.orderedStops()).isEmpty();
		assertThat(route.totalDistanceKm()).isZero();
		assertThat(route.totalLoad()).isZero();
	}

	@Test
	void clusterOfIdenticalCoords() {
		List<PlannedOrder> atCp = List.of(
				DispatchFixtures.order("A", DispatchFixtures.CP_LAT, DispatchFixtures.CP_LON, CP, 5, Priority.HIGH),
				DispatchFixtures.order("B", DispatchFixtures.CP_LAT, DispatchFixtures.CP_LON, CP, 5, Priority.HIGH),
				DispatchFixtures.order("C", DispatchFixtures.CP_LAT, DispatchFixtures.CP_LON, CP, 5, Priority.HIGH),
				DispatchFixtures.order("D", DispatchFixtures.CP_LAT, DispatchFixtures.CP_LON, CP, 5, Priority.HIGH));
		VehicleRoute route = planner.plan(CP_VEHICLE, atCp);
		assertThat(route.legs()).hasSize(5);
		assertThat(route.legs()).allSatisfy(leg -> assertThat(leg.distanceKm()).isZero());
		assertThat(route.totalDistanceKm()).isZero();
	}

	@Test
	void reorderingNeverDropsOrDuplicates() {
		List<PlannedOrder> stops = DispatchFixtures.delhiOrders().stream()
				.filter(o -> o.priority() == Priority.HIGH)
				.collect(Collectors.toList());
		VehicleRoute route = planner.plan(new PlannedVehicle("VEH-BIG", 1000,
				DispatchFixtures.CP_LAT, DispatchFixtures.CP_LON, CP), stops);
		assertThat(route.orderedStops().stream().map(s -> s.order().orderId()).toList())
				.containsExactlyInAnyOrderElementsOf(
						stops.stream().map(PlannedOrder::orderId).toList());
	}

}
