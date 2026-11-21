package com.bunny.dispatch.service.dispatch.impl;

import com.bunny.dispatch.dto.request.OrderRequest;
import com.bunny.dispatch.dto.request.OrdersBatchRequest;
import com.bunny.dispatch.dto.request.VehicleRequest;
import com.bunny.dispatch.dto.request.VehiclesBatchRequest;
import com.bunny.dispatch.dto.response.AckResponse;
import com.bunny.dispatch.dto.response.AssignedOrderDto;
import com.bunny.dispatch.dto.response.ClearDataResponse;
import com.bunny.dispatch.dto.response.DispatchPlanResponse;
import com.bunny.dispatch.dto.response.VehiclePlanDto;
import com.bunny.dispatch.entity.OrderEntity;
import com.bunny.dispatch.entity.VehicleEntity;
import com.bunny.dispatch.entity.common.GeoPoint;
import com.bunny.dispatch.entity.common.Priority;
import com.bunny.dispatch.entity.common.UnassignedReason;
import com.bunny.dispatch.exception.DuplicateInBatchException;
import com.bunny.dispatch.exception.NoDispatchDataException;
import com.bunny.dispatch.mapper.DispatchMapper;
import com.bunny.dispatch.mapper.DispatchMapperImpl;
import com.bunny.dispatch.repository.OrderRepository;
import com.bunny.dispatch.repository.VehicleRepository;
import com.bunny.dispatch.service.dispatch.handler.AssignmentStrategy;
import com.bunny.dispatch.service.dispatch.handler.GreedyAssignmentStrategy;
import com.bunny.dispatch.service.dispatch.handler.model.AssignedStop;
import com.bunny.dispatch.service.dispatch.handler.model.AssignmentResult;
import com.bunny.dispatch.service.dispatch.handler.model.PlannedOrder;
import com.bunny.dispatch.service.dispatch.handler.model.PlannedVehicle;
import com.bunny.dispatch.service.dispatch.handler.model.RouteLeg;
import com.bunny.dispatch.service.dispatch.handler.model.UnassignedOrder;
import com.bunny.dispatch.service.dispatch.handler.model.VehicleRoute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.withinPercentage;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DispatchServiceImplTest {

	// Bangalore spec sample — round-trip goldens measured 2026-11-20
	// (independent Node.js and jshell computations of the same formula, identical to 6 dp):
	// VEH001 Indiranagar -> MG Road -> back = 2 * 5.060290 = 10.120580 km
	// VEH002 T Nagar     -> Anna Salai -> back = 2 * 3.968316 = 7.936633 km
	private static final double BLR_ROUND_TRIP_VEH001 = 10.120580;
	private static final double BLR_ROUND_TRIP_VEH002 = 7.936633;

	@Mock
	private OrderRepository orderRepository;
	@Mock
	private VehicleRepository vehicleRepository;

	private final DispatchMapper mapper = new DispatchMapperImpl();

	private DispatchServiceImpl serviceWithRealStrategy() {
		return new DispatchServiceImpl(orderRepository, vehicleRepository, mapper, new GreedyAssignmentStrategy());
	}

	private DispatchServiceImpl serviceWithStrategy(AssignmentStrategy strategy) {
		return new DispatchServiceImpl(orderRepository, vehicleRepository, mapper, strategy);
	}

	private static OrderRequest order(String id, double lat, double lon, String address, int weight, Priority p) {
		return new OrderRequest(id, lat, lon, address, weight, p);
	}

	private static OrderEntity orderEntity(String id, double lat, double lon, String address, int weight, Priority p) {
		return OrderEntity.builder()
				.orderId(id)
				.location(new GeoPoint(lat, lon))
				.address(address)
				.packageWeight(weight)
				.priority(p)
				.createdAt(LocalDateTime.now())
				.updatedAt(LocalDateTime.now())
				.build();
	}

	private static VehicleEntity vehicleEntity(String id, int capacity, double lat, double lon, String address) {
		return VehicleEntity.builder()
				.vehicleId(id)
				.capacity(capacity)
				.currentPoint(new GeoPoint(lat, lon))
				.currentAddress(address)
				.createdAt(LocalDateTime.now())
				.updatedAt(LocalDateTime.now())
				.build();
	}

	private static List<OrderEntity> bangaloreOrders() {
		return List.of(
				orderEntity("ORD001", 12.9716, 77.5946, "MG Road, Bangalore, Karnataka, India", 10, Priority.HIGH),
				orderEntity("ORD002", 13.0827, 80.2707, "Anna Salai, Chennai, Tamil Nadu, India", 20, Priority.MEDIUM));
	}

	private static List<VehicleEntity> bangaloreVehicles() {
		return List.of(
				vehicleEntity("VEH001", 100, 12.9716, 77.6413, "Indiranagar, Bangalore, Karnataka, India"),
				vehicleEntity("VEH002", 150, 13.0674, 80.2376, "T Nagar, Chennai, Tamil Nadu, India"));
	}

	@Test
	void duplicateOrderIdInBatch_throwsBeforeAnyDbTouch() {
		OrdersBatchRequest batch = new OrdersBatchRequest(List.of(
				order("ORD001", 1.0, 1.0, "A", 5, Priority.HIGH),
				order("ORD005", 2.0, 2.0, "B", 5, Priority.HIGH),
				order("ORD005", 3.0, 3.0, "C", 5, Priority.LOW)));
		DispatchServiceImpl service = serviceWithRealStrategy();
		assertThatThrownBy(() -> service.ingestOrders(batch))
				.isInstanceOf(DuplicateInBatchException.class)
				.hasMessage("Duplicate orderId in request: ORD005");
		verifyNoInteractions(orderRepository);
	}

	@Test
	void duplicateVehicleIdInBatch_throwsBeforeAnyDbTouch() {
		VehiclesBatchRequest batch = new VehiclesBatchRequest(List.of(
				new VehicleRequest("VEH001", 100, 1.0, 1.0, "Depot A"),
				new VehicleRequest("VEH002", 100, 2.0, 2.0, "Depot B"),
				new VehicleRequest("VEH002", 200, 3.0, 3.0, "Depot C")));
		DispatchServiceImpl service = serviceWithRealStrategy();
		assertThatThrownBy(() -> service.ingestVehicles(batch))
				.isInstanceOf(DuplicateInBatchException.class)
				.hasMessage("Duplicate vehicleId in request: VEH002");
		verifyNoInteractions(vehicleRepository);
	}

	@Test
	void ingestCounts_receivedInsertedUpdated_andExistingIsUpdatedInPlace() {
		OrderEntity existingManaged = orderEntity("ORD001", 1.0, 1.0, "Old Address", 1, Priority.LOW);
		LocalDateTime originalCreatedAt = existingManaged.getCreatedAt();
		when(orderRepository.findAllById(anyIterable())).thenReturn(List.of(existingManaged));

		OrdersBatchRequest batch = new OrdersBatchRequest(List.of(
				order("ORD001", 9.0, 9.0, "New Address", 99, Priority.HIGH),
				order("ORD002", 2.0, 2.0, "B", 5, Priority.HIGH),
				order("ORD003", 3.0, 3.0, "C", 5, Priority.MEDIUM)));
		AckResponse ack = serviceWithRealStrategy().ingestOrders(batch);

		assertThat(ack.received()).isEqualTo(3);
		assertThat(ack.inserted()).isEqualTo(2);
		assertThat(ack.updated()).isEqualTo(1);
		assertThat(ack.message()).isEqualTo("Delivery orders accepted.");
		verify(orderRepository, times(2)).save(any(OrderEntity.class));
		// load-and-copy proof: values replaced on the managed entity, createdAt untouched
		assertThat(existingManaged.getPackageWeight()).isEqualTo(99);
		assertThat(existingManaged.getAddress()).isEqualTo("New Address");
		assertThat(existingManaged.getCreatedAt()).isEqualTo(originalCreatedAt);
	}

	@Test
	void reingestVehicle_countsUpdatedAndReplacesValuesOnTheManagedEntity() {
		VehicleEntity existingManaged = vehicleEntity("VEH001", 100, 1.0, 1.0, "Old Depot");
		LocalDateTime originalCreatedAt = existingManaged.getCreatedAt();
		when(vehicleRepository.findAllById(anyIterable())).thenReturn(List.of(existingManaged));

		VehiclesBatchRequest batch = new VehiclesBatchRequest(List.of(
				new VehicleRequest("VEH001", 250, 9.0, 9.0, "New Depot"),
				new VehicleRequest("VEH009", 80, 3.0, 3.0, "Depot C")));
		AckResponse ack = serviceWithRealStrategy().ingestVehicles(batch);

		assertThat(ack.received()).isEqualTo(2);
		assertThat(ack.inserted()).isEqualTo(1);
		assertThat(ack.updated()).isEqualTo(1);
		assertThat(ack.message()).isEqualTo("Vehicle details accepted.");
		verify(vehicleRepository, times(1)).save(any(VehicleEntity.class));
		// the managed instance is updated in place, never replaced by a detached copy
		assertThat(existingManaged.getCapacity()).isEqualTo(250);
		assertThat(existingManaged.getCurrentAddress()).isEqualTo("New Depot");
		assertThat(existingManaged.getCurrentPoint().getLatitude()).isEqualTo(9.0);
		assertThat(existingManaged.getCurrentPoint().getLongitude()).isEqualTo(9.0);
		assertThat(existingManaged.getCreatedAt()).isEqualTo(originalCreatedAt);
	}

	@Test
	void buildPlan_bothEmpty_throwsNoDispatchData() {
		when(orderRepository.findAll()).thenReturn(List.of());
		when(vehicleRepository.findAll()).thenReturn(List.of());
		assertThatThrownBy(() -> serviceWithRealStrategy().buildPlan())
				.isInstanceOf(NoDispatchDataException.class)
				.hasMessage("No dispatch data available. Post orders and vehicles first.");
	}

	@Test
	void buildPlan_ordersOnly_doesNotThrow() {
		when(orderRepository.findAll()).thenReturn(bangaloreOrders());
		when(vehicleRepository.findAll()).thenReturn(List.of());
		DispatchPlanResponse response = serviceWithRealStrategy().buildPlan();
		assertThat(response.dispatchPlan()).isEmpty();
	}

	@Test
	void vehiclesEmpty_allOrdersUnassignedNoVehiclesAvailable() {
		when(orderRepository.findAll()).thenReturn(bangaloreOrders());
		when(vehicleRepository.findAll()).thenReturn(List.of());
		DispatchPlanResponse response = serviceWithRealStrategy().buildPlan();

		assertThat(response.unassignedOrders()).hasSize(2);
		assertThat(response.unassignedOrders())
				.allSatisfy(u -> assertThat(u.reason()).isEqualTo(UnassignedReason.NO_VEHICLES_AVAILABLE));
		assertThat(response.totals().assignedOrderCount()).isZero();
		assertThat(response.totals().unassignedOrderCount()).isEqualTo(2);
	}

	@Test
	void kmStringRounding_displayIsRounded_numericStaysExact() {
		PlannedVehicle vehicle = new PlannedVehicle("VEH-X", 100, 0.0, 0.0, "Depot");
		PlannedOrder order = new PlannedOrder("ORD-X", 1.0, 1.0, "Stop", 10, Priority.HIGH);
		RouteLeg out = new RouteLeg("Depot", "Stop", 4.6);
		RouteLeg back = new RouteLeg("Stop", "Depot", 4.6);
		VehicleRoute route = new VehicleRoute(vehicle,
				List.of(new AssignedStop(order, out)), List.of(out, back), 9.2);
		AssignmentStrategy stub = (orders, vehicles) -> new AssignmentResult(
				List.of(route), List.of(new UnassignedOrder(order, UnassignedReason.EXCEEDS_ALL_VEHICLE_CAPACITIES)));
		when(orderRepository.findAll()).thenReturn(List.of(orderEntity("ORD-X", 1.0, 1.0, "Stop", 10, Priority.HIGH)));
		when(vehicleRepository.findAll()).thenReturn(List.of(vehicleEntity("VEH-X", 100, 0.0, 0.0, "Depot")));

		DispatchPlanResponse response = serviceWithStrategy(stub).buildPlan();

		VehiclePlanDto dto = response.dispatchPlan().get(0);
		assertThat(dto.totalDistance()).isEqualTo("9 km");
		assertThat(dto.totalDistanceKm()).isEqualTo(9.2);
		assertThat(VehiclePlanDto.formatKm(4.6)).isEqualTo("5 km");
		assertThat(VehiclePlanDto.formatKm(0.4)).isEqualTo("0 km");
	}

	@Test
	void totalsAreSumsOfPlan_bangaloreGoldens() {
		when(orderRepository.findAll()).thenReturn(bangaloreOrders());
		when(vehicleRepository.findAll()).thenReturn(bangaloreVehicles());

		DispatchPlanResponse response = serviceWithRealStrategy().buildPlan();

		assertThat(response.dispatchPlan()).hasSize(2);
		VehiclePlanDto veh001 = response.dispatchPlan().get(0);
		VehiclePlanDto veh002 = response.dispatchPlan().get(1);
		assertThat(veh001.vehicleId()).isEqualTo("VEH001");
		assertThat(veh001.totalLoad()).isEqualTo(10);
		assertThat(veh001.totalDistance()).isEqualTo("10 km");
		assertThat(veh001.totalDistanceKm()).isCloseTo(BLR_ROUND_TRIP_VEH001, withinPercentage(0.5));
		assertThat(veh002.vehicleId()).isEqualTo("VEH002");
		assertThat(veh002.totalLoad()).isEqualTo(20);
		assertThat(veh002.totalDistance()).isEqualTo("8 km");
		assertThat(veh002.totalDistanceKm()).isCloseTo(BLR_ROUND_TRIP_VEH002, withinPercentage(0.5));

		assertThat(veh001.legs()).hasSize(2);
		assertThat(veh001.legs().get(0).fromAddress()).isEqualTo("Indiranagar, Bangalore, Karnataka, India");
		assertThat(veh001.legs().get(1).toAddress()).isEqualTo("Indiranagar, Bangalore, Karnataka, India");

		assertThat(response.totals().assignedOrderCount()).isEqualTo(2);
		assertThat(response.totals().unassignedOrderCount()).isZero();
		assertThat(response.totals().totalLoadKg()).isEqualTo(30);
		assertThat(response.totals().totalDistanceKm())
				.isCloseTo(BLR_ROUND_TRIP_VEH001 + BLR_ROUND_TRIP_VEH002, withinPercentage(0.5));
	}

	@Test
	void planEchoesFullOrderFields() {
		when(orderRepository.findAll()).thenReturn(bangaloreOrders());
		when(vehicleRepository.findAll()).thenReturn(bangaloreVehicles());

		DispatchPlanResponse response = serviceWithRealStrategy().buildPlan();
		AssignedOrderDto echo = response.dispatchPlan().get(0).assignedOrders().get(0);

		assertThat(echo.orderId()).isEqualTo("ORD001");
		assertThat(echo.latitude()).isEqualTo(12.9716);
		assertThat(echo.longitude()).isEqualTo(77.5946);
		assertThat(echo.address()).isEqualTo("MG Road, Bangalore, Karnataka, India");
		assertThat(echo.packageWeight()).isEqualTo(10);
		assertThat(echo.priority()).isEqualTo(Priority.HIGH);
	}

	@Test
	void clearData_returnsCountsCapturedBeforeDelete() {
		when(orderRepository.count()).thenReturn(7L);
		when(vehicleRepository.count()).thenReturn(3L);

		ClearDataResponse response = serviceWithRealStrategy().clearData();

		assertThat(response.deletedOrders()).isEqualTo(7);
		assertThat(response.deletedVehicles()).isEqualTo(3);
		assertThat(response.message()).isEqualTo("Dispatch data cleared.");
		verify(orderRepository).deleteAllInBatch();
		verify(vehicleRepository).deleteAllInBatch();
	}

	@Test
	void buildPlanRecomputesFreshEveryCall() {
		when(orderRepository.findAll()).thenReturn(bangaloreOrders());
		when(vehicleRepository.findAll()).thenReturn(bangaloreVehicles());
		DispatchServiceImpl service = serviceWithRealStrategy();

		service.buildPlan();
		service.buildPlan();

		verify(orderRepository, times(2)).findAll();
		verify(vehicleRepository, times(2)).findAll();
		verify(orderRepository, never()).save(any());
	}

}
