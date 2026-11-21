package com.bunny.dispatch;

import com.bunny.dispatch.dto.request.OrderRequest;
import com.bunny.dispatch.dto.response.AckResponse;
import com.bunny.dispatch.dto.response.AssignedOrderDto;
import com.bunny.dispatch.dto.response.DispatchPlanResponse;
import com.bunny.dispatch.dto.response.RouteLegDto;
import com.bunny.dispatch.dto.response.UnassignedOrderDto;
import com.bunny.dispatch.dto.response.VehiclePlanDto;
import com.bunny.dispatch.entity.common.Priority;
import com.bunny.dispatch.entity.common.UnassignedReason;
import com.bunny.dispatch.support.SampleData;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE evaluation centerpiece: the requirement doc's own Delhi sample (645 kg of demand against
 * a 500 kg fleet, so a perfect plan is impossible by design) pushed through real HTTP into real
 * Postgres, and the plan pulled back out through real HTTP. The asserted outcome is the measured
 * D12 invariant from {@code GreedyAssignmentStrategyTest.delhiOverflowInvariant} — the same
 * numbers, now proven to survive SQL, mapping and serialization.
 */
class DispatchPlanE2EIT extends AbstractE2ETest {

	private static final List<String> HIGH_IDS = List.of(
			"ORD001", "ORD004", "ORD007", "ORD008", "ORD011", "ORD013",
			"ORD015", "ORD018", "ORD020", "ORD023", "ORD026", "ORD029");
	private static final List<String> MEDIUM_IDS = List.of(
			"ORD002", "ORD005", "ORD009", "ORD012", "ORD017", "ORD021", "ORD025", "ORD027");
	private static final List<String> LOW_IDS_THAT_ALSO_FIT = List.of("ORD010", "ORD014", "ORD016");
	private static final List<String> UNASSIGNED_IDS = List.of(
			"ORD003", "ORD006", "ORD019", "ORD022", "ORD024", "ORD028", "ORD030");
	private static final int UNASSIGNED_KG = 150;
	private static final int ASSIGNED_KG = 495;
	private static final int ASSIGNED_COUNT = 23;

	@Test
	void delhiGoldenPlan_matchesMeasuredInvariantAndSpecAcks() throws Exception {
		AckResponse vehiclesAck = readJson(postForBody(VEHICLES_URL, SampleData.vehiclesJson("delhi-vehicles.json")),
				AckResponse.class);
		assertThat(vehiclesAck.message()).isEqualTo("Vehicle details accepted.");
		assertThat(vehiclesAck.status()).isEqualTo("success");
		assertThat(vehiclesAck.received()).isEqualTo(5);
		assertThat(vehiclesAck.inserted()).isEqualTo(5);
		assertThat(vehiclesAck.updated()).isZero();

		AckResponse ordersAck = readJson(postForBody(ORDERS_URL, SampleData.ordersJson("delhi-orders.json")),
				AckResponse.class);
		assertThat(ordersAck.message()).isEqualTo("Delivery orders accepted.");
		assertThat(ordersAck.status()).isEqualTo("success");
		assertThat(ordersAck.received()).isEqualTo(30);
		assertThat(ordersAck.inserted()).isEqualTo(30);
		assertThat(ordersAck.updated()).isZero();

		DispatchPlanResponse plan = plan();

		assertThat(assignedIds(plan)).containsAll(HIGH_IDS);
		assertThat(assignedIds(plan)).containsAll(MEDIUM_IDS);
		assertThat(assignedIds(plan)).containsAll(LOW_IDS_THAT_ALSO_FIT);
		assertThat(assignedIds(plan)).hasSize(ASSIGNED_COUNT);

		assertThat(plan.unassignedOrders()).extracting("orderId")
				.containsExactlyInAnyOrderElementsOf(UNASSIGNED_IDS);
		assertThat(plan.unassignedOrders().stream().mapToInt(UnassignedOrderDto::packageWeight).sum())
				.isEqualTo(UNASSIGNED_KG);
		assertThat(plan.unassignedOrders()).extracting("priority").containsOnly(Priority.LOW);
		assertThat(plan.unassignedOrders()).extracting("reason")
				.containsOnly(UnassignedReason.NO_VEHICLE_WITH_SUFFICIENT_REMAINING_CAPACITY);

		assertThat(plan.totals().assignedOrderCount()).isEqualTo(ASSIGNED_COUNT);
		assertThat(plan.totals().unassignedOrderCount()).isEqualTo(UNASSIGNED_IDS.size());
		assertThat(plan.totals().totalLoadKg()).isEqualTo(ASSIGNED_KG);
	}

	@Test
	void delhiPlan_neverExceedsAnyVehicleCapacity() throws Exception {
		postDelhiBatches();
		Map<String, Integer> capacityById = SampleData.vehicles("delhi-vehicles.json").vehicles().stream()
				.collect(Collectors.toMap(v -> v.vehicleId(), v -> v.capacity()));

		DispatchPlanResponse plan = plan();

		assertThat(plan.dispatchPlan()).isNotEmpty();
		int sum = 0;
		for (VehiclePlanDto vehicle : plan.dispatchPlan()) {
			Integer capacity = capacityById.get(vehicle.vehicleId());
			assertThat(capacity).as("plan references unknown vehicle").isNotNull();
			assertThat(vehicle.totalLoad())
					.as("%s load %d over capacity %d", vehicle.vehicleId(), vehicle.totalLoad(), capacity)
					.isLessThanOrEqualTo(capacity);
			assertThat(vehicle.totalLoad())
					.isEqualTo(vehicle.assignedOrders().stream().mapToInt(AssignedOrderDto::packageWeight).sum());
			sum += vehicle.totalLoad();
		}
		assertThat(sum).isEqualTo(ASSIGNED_KG);
	}

	@Test
	void delhiPlan_distancesAreSelfConsistentRoundTrips() throws Exception {
		postDelhiBatches();
		Map<String, String> startAddressById = SampleData.vehicles("delhi-vehicles.json").vehicles().stream()
				.collect(Collectors.toMap(v -> v.vehicleId(), v -> v.currentAddress(),
						(a, b) -> a, LinkedHashMap::new));
		Function<VehiclePlanDto, Double> legsSum = v -> v.legs().stream()
				.mapToDouble(RouteLegDto::distanceKm).sum();

		DispatchPlanResponse plan = plan();

		for (VehiclePlanDto vehicle : plan.dispatchPlan()) {
			String label = "vehicle " + vehicle.vehicleId();
			assertThat(vehicle.legs()).as(label).isNotEmpty();
			// round trip (D7): leave from and return to the vehicle's own current address
			assertThat(vehicle.legs().get(0).fromAddress()).as(label).isEqualTo(startAddressById.get(vehicle.vehicleId()));
			assertThat(vehicle.legs().get(vehicle.legs().size() - 1).toAddress())
					.as(label).isEqualTo(startAddressById.get(vehicle.vehicleId()));
			// N stops => N+1 legs; every leg except the final return leg lands on an assigned
			// order, in the order the vehicle visits them
			assertThat(vehicle.legs()).as(label).hasSize(vehicle.assignedOrders().size() + 1);
			assertThat(vehicle.legs().subList(0, vehicle.legs().size() - 1).stream()
					.map(RouteLegDto::toAddress).toList())
					.as(label).containsExactlyElementsOf(
							vehicle.assignedOrders().stream().map(AssignedOrderDto::address).toList());
			// legs form one unbroken chain — no teleporting between consecutive stops
			for (int i = 1; i < vehicle.legs().size(); i++) {
				assertThat(vehicle.legs().get(i).fromAddress()).as(label + " leg " + i)
						.isEqualTo(vehicle.legs().get(i - 1).toAddress());
			}
			// the emitted numeric total IS the sum of the emitted legs — hand-verifiable from the response alone
			assertThat(vehicle.totalDistanceKm()).as(label).isCloseTo(legsSum.apply(vehicle), within(1e-9));
			assertThat(vehicle.totalDistance()).as(label)
					.isEqualTo(VehiclePlanDto.formatKm(vehicle.totalDistanceKm()));
		}
		assertThat(plan.totals().totalDistanceKm())
				.isCloseTo(plan.dispatchPlan().stream().mapToDouble(VehiclePlanDto::totalDistanceKm).sum(), within(1e-9));
	}

	@Test
	void delhiPlan_echoesEveryAssignedOrderFieldForVisualization() throws Exception {
		postDelhiBatches();
		Map<String, OrderRequest> requestById =
				SampleData.orders("delhi-orders.json").orders().stream()
						.collect(Collectors.toMap(OrderRequest::orderId, Function.identity()));

		AssignedOrderDto first = plan().dispatchPlan().get(0).assignedOrders().get(0);
		OrderRequest request = requestById.get(first.orderId());

		assertThat(first.address()).isEqualTo(request.address());
		assertThat(first.latitude()).isCloseTo(request.latitude(), within(1e-9));
		assertThat(first.longitude()).isCloseTo(request.longitude(), within(1e-9));
		assertThat(first.packageWeight()).isEqualTo(request.packageWeight());
		assertThat(first.priority()).isEqualTo(request.priority());
	}

	@Test
	void delhiPlan_isRecomputedFreshAndIdenticallyOnEveryCall() throws Exception {
		postDelhiBatches();
		String firstBody = planBody();

		// second GET with zero writes: byte-identical body proves both D6 (no cache — it reads
		// the DB again) and D13 (determinism — same input, same output)
		assertThat(planBody()).isEqualTo(firstBody);
	}

	@Test
	void bangaloreSpecSample_conformsToSpecShapeWithMeasuredDistances() throws Exception {
		postSample(VEHICLES_URL, SampleData.vehiclesJson("bangalore-vehicles.json"));
		postSample(ORDERS_URL, SampleData.ordersJson("bangalore-orders.json"));

		// literal spec field names, asserted on raw JSON (not via DTOs) so a renamed record
		// component cannot silently pass
		mockMvc.perform(get(PLAN_URL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dispatchPlan").isArray())
				.andExpect(jsonPath("$.dispatchPlan[0].vehicleId").value("VEH001"))
				.andExpect(jsonPath("$.dispatchPlan[0].currentAddress").value("Indiranagar, Bangalore, Karnataka, India"))
				.andExpect(jsonPath("$.dispatchPlan[0].totalLoad").value(10))
				.andExpect(jsonPath("$.dispatchPlan[0].totalDistance").value("10 km"))
				.andExpect(jsonPath("$.dispatchPlan[0].assignedOrders[0].orderId").value("ORD001"))
				.andExpect(jsonPath("$.dispatchPlan[0].assignedOrders[0].priority").value("HIGH"))
				.andExpect(jsonPath("$.dispatchPlan[0].assignedOrders[0].address")
						.value("MG Road, Bangalore, Karnataka, India"))
				.andExpect(jsonPath("$.dispatchPlan[1].vehicleId").value("VEH002"))
				.andExpect(jsonPath("$.dispatchPlan[1].totalLoad").value(20))
				.andExpect(jsonPath("$.dispatchPlan[1].totalDistance").value("8 km"))
				.andExpect(jsonPath("$.dispatchPlan[1].assignedOrders[0].orderId").value("ORD002"))
				.andExpect(jsonPath("$.unassignedOrders").isArray())
				.andExpect(jsonPath("$.unassignedOrders").isEmpty())
				.andExpect(jsonPath("$.totals.assignedOrderCount").value(2))
				.andExpect(jsonPath("$.totals.totalLoadKg").value(30));

		// distances themselves are the twice-measured round-trip goldens (Part 5): the spec's
		// illustrative "5 km"/"6 km" are one-way; ours are start -> stop -> start
		DispatchPlanResponse plan = plan();
		assertThat(plan.dispatchPlan().get(0).totalDistanceKm()).isCloseTo(10.120580, within(0.06));
		assertThat(plan.dispatchPlan().get(1).totalDistanceKm()).isCloseTo(7.936633, within(0.04));
		assertThat(plan.dispatchPlan().get(0).legs()).hasSize(2);
		assertThat(plan.dispatchPlan().get(1).legs()).hasSize(2);
	}

	private static List<String> assignedIds(DispatchPlanResponse plan) {
		return plan.dispatchPlan().stream()
				.flatMap(v -> v.assignedOrders().stream())
				.map(AssignedOrderDto::orderId)
				.sorted()
				.toList();
	}

}
