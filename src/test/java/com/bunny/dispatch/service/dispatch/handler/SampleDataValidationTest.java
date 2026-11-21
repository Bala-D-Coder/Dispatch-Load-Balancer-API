package com.bunny.dispatch.service.dispatch.handler;

import com.bunny.dispatch.dto.request.OrderRequest;
import com.bunny.dispatch.dto.request.VehicleRequest;
import com.bunny.dispatch.service.dispatch.handler.model.PlannedOrder;
import com.bunny.dispatch.service.dispatch.handler.model.PlannedVehicle;
import com.bunny.dispatch.support.SampleData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Third-transcription fence (no Docker, no Spring). The four sample JSON files were typed
 * directly from requirements.txt — NOT copied from {@link DispatchFixtures} (transcription #2).
 * The literal sums below are the measured D12 numbers from the master plan; the cross-check
 * test then proves transcription #3 == #2 field by field, so a typo in either fails the build.
 */
class SampleDataValidationTest {

	private static final int DELHI_DEMAND_KG = 645;
	private static final int DELHI_FLEET_CAPACITY_KG = 500;

	private static final List<OrderRequest> delhiOrders = SampleData.orders("delhi-orders.json").orders();
	private static final List<VehicleRequest> delhiVehicles = SampleData.vehicles("delhi-vehicles.json").vehicles();

	@Test
	void delhiTotalsMatchMeasuredInvariant() {
		assertThat(delhiOrders).hasSize(30);
		assertThat(delhiVehicles).hasSize(5);
		assertThat(sumWeights(delhiOrders)).isEqualTo(DELHI_DEMAND_KG);
		assertThat(delhiVehicles.stream().mapToLong(VehicleRequest::capacity).sum())
				.isEqualTo(DELHI_FLEET_CAPACITY_KG);
	}

	@Test
	void delhiPrioritySplitMatchesMeasuredInvariant() {
		Map<String, List<OrderRequest>> byPriority = delhiOrders.stream()
				.collect(Collectors.groupingBy(o -> o.priority().name()));
		assertThat(byPriority.get("HIGH")).hasSize(12);
		assertThat(sumWeights(byPriority.get("HIGH"))).isEqualTo(275);
		assertThat(byPriority.get("MEDIUM")).hasSize(8);
		assertThat(sumWeights(byPriority.get("MEDIUM"))).isEqualTo(145);
		assertThat(byPriority.get("LOW")).hasSize(10);
		assertThat(sumWeights(byPriority.get("LOW"))).isEqualTo(225);
	}

	@Test
	void delhiJsonMatchesJavaFixtureFieldByField() {
		List<PlannedOrder> fixture = DispatchFixtures.delhiOrders();
		assertThat(delhiOrders).hasSameSizeAs(fixture);
		Map<String, PlannedOrder> byId = fixture.stream()
				.collect(Collectors.toMap(PlannedOrder::orderId, Function.identity()));
		for (OrderRequest json : delhiOrders) {
			PlannedOrder expected = byId.get(json.orderId());
			assertThat(expected).as("fixture has " + json.orderId()).isNotNull();
			assertThat(json.latitude()).isCloseTo(expected.latitude(), within(1e-9));
			assertThat(json.longitude()).isCloseTo(expected.longitude(), within(1e-9));
			assertThat(json.address()).isEqualTo(expected.address());
			assertThat(json.packageWeight()).isEqualTo(expected.packageWeight());
			assertThat(json.priority()).isEqualTo(expected.priority());
		}
	}

	@Test
	void delhiVehiclesMatchJavaFixtureFieldByField() {
		List<PlannedVehicle> fixture = DispatchFixtures.delhiVehicles();
		Map<String, PlannedVehicle> byId = fixture.stream()
				.collect(Collectors.toMap(PlannedVehicle::vehicleId, Function.identity()));
		for (VehicleRequest json : delhiVehicles) {
			PlannedVehicle expected = byId.get(json.vehicleId());
			assertThat(expected).as("fixture has " + json.vehicleId()).isNotNull();
			assertThat(json.capacity()).isEqualTo(expected.capacity());
			assertThat(json.currentLatitude()).isCloseTo(expected.currentLatitude(), within(1e-9));
			assertThat(json.currentLongitude()).isCloseTo(expected.currentLongitude(), within(1e-9));
			assertThat(json.currentAddress()).isEqualTo(expected.currentAddress());
		}
	}

	@Test
	void bangaloreSamplesMatchSpecDocument() {
		List<OrderRequest> orders = SampleData.orders("bangalore-orders.json").orders();
		List<VehicleRequest> vehicles = SampleData.vehicles("bangalore-vehicles.json").vehicles();
		assertThat(orders).hasSize(2);
		assertThat(sumWeights(orders)).isEqualTo(30);
		assertThat(vehicles).hasSize(2);
		assertThat(vehicles).extracting(VehicleRequest::capacity).containsExactly(100, 150);
	}

	private static long sumWeights(List<OrderRequest> orders) {
		return orders.stream().mapToLong(OrderRequest::packageWeight).sum();
	}

}
