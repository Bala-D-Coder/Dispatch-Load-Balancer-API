package com.bunny.dispatch.mapper;

import com.bunny.dispatch.dto.request.OrderRequest;
import com.bunny.dispatch.dto.request.VehicleRequest;
import com.bunny.dispatch.dto.response.AssignedOrderDto;
import com.bunny.dispatch.entity.OrderEntity;
import com.bunny.dispatch.entity.VehicleEntity;
import com.bunny.dispatch.entity.common.GeoPoint;
import com.bunny.dispatch.entity.common.Priority;
import com.bunny.dispatch.service.dispatch.handler.model.PlannedOrder;
import com.bunny.dispatch.service.dispatch.handler.model.PlannedVehicle;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DispatchMapperTest {

	private final DispatchMapper mapper = new DispatchMapperImpl();

	private static OrderRequest orderRequest(String id, int weight) {
		return new OrderRequest(id, 12.9716, 77.5946, "MG Road, Bangalore, Karnataka, India", weight, Priority.HIGH);
	}

	@Test
	void requestToEntity_mapsEmbeddedPoint() {
		OrderEntity entity = mapper.toEntity(orderRequest("ORD001", 10));
		assertThat(entity.getOrderId()).isEqualTo("ORD001");
		assertThat(entity.getLocation()).isNotNull();
		assertThat(entity.getLocation().getLatitude()).isEqualTo(12.9716);
		assertThat(entity.getLocation().getLongitude()).isEqualTo(77.5946);
		assertThat(entity.getPackageWeight()).isEqualTo(10);
		assertThat(entity.getPriority()).isEqualTo(Priority.HIGH);
		assertThat(entity.getCreatedAt()).isNull();
		assertThat(entity.getUpdatedAt()).isNull();
	}

	@Test
	void updateCopiesValues_notTimestamps() {
		LocalDateTime originalCreatedAt = LocalDateTime.of(2026, 1, 1, 10, 0);
		LocalDateTime originalUpdatedAt = LocalDateTime.of(2026, 1, 2, 10, 0);
		OrderEntity target = OrderEntity.builder()
				.orderId("ORD001")
				.location(new GeoPoint(1.0, 2.0))
				.address("Old Address")
				.packageWeight(5)
				.priority(Priority.LOW)
				.createdAt(originalCreatedAt)
				.updatedAt(originalUpdatedAt)
				.build();

		mapper.update(orderRequest("ORD001", 99), target);

		assertThat(target.getPackageWeight()).isEqualTo(99);
		assertThat(target.getAddress()).isEqualTo("MG Road, Bangalore, Karnataka, India");
		assertThat(target.getPriority()).isEqualTo(Priority.HIGH);
		assertThat(target.getLocation().getLatitude()).isEqualTo(12.9716);
		// the merge-nulls fence: upsert must never erase DB-managed timestamps
		assertThat(target.getCreatedAt()).isEqualTo(originalCreatedAt);
		assertThat(target.getUpdatedAt()).isEqualTo(originalUpdatedAt);
	}

	@Test
	void entityToPlanned_flattensGeoPoint() {
		OrderEntity entity = mapper.toEntity(orderRequest("ORD001", 10));
		PlannedOrder planned = mapper.toPlanned(entity);
		assertThat(planned.orderId()).isEqualTo("ORD001");
		assertThat(planned.latitude()).isEqualTo(12.9716);
		assertThat(planned.longitude()).isEqualTo(77.5946);
		assertThat(planned.address()).isEqualTo("MG Road, Bangalore, Karnataka, India");
		assertThat(planned.packageWeight()).isEqualTo(10);
		assertThat(planned.priority()).isEqualTo(Priority.HIGH);
	}

	@Test
	void vehicleMappingsBothDirections() {
		VehicleRequest request = new VehicleRequest("VEH001", 100, 12.9716, 77.6413,
				"Indiranagar, Bangalore, Karnataka, India");
		VehicleEntity entity = mapper.toEntity(request);
		assertThat(entity.getCurrentPoint().getLatitude()).isEqualTo(12.9716);
		assertThat(entity.getCurrentPoint().getLongitude()).isEqualTo(77.6413);

		PlannedVehicle planned = mapper.toPlanned(entity);
		assertThat(planned.vehicleId()).isEqualTo("VEH001");
		assertThat(planned.capacity()).isEqualTo(100);
		assertThat(planned.currentLatitude()).isEqualTo(request.currentLatitude());
		assertThat(planned.currentLongitude()).isEqualTo(request.currentLongitude());
		assertThat(planned.currentAddress()).isEqualTo(request.currentAddress());
	}

	@Test
	void plannedToDto_fullEcho() {
		PlannedOrder planned = new PlannedOrder("ORD002", 13.0827, 80.2707,
				"Anna Salai, Chennai, Tamil Nadu, India", 20, Priority.MEDIUM);
		AssignedOrderDto dto = mapper.toDto(planned);
		assertThat(dto.orderId()).isEqualTo("ORD002");
		assertThat(dto.latitude()).isEqualTo(13.0827);
		assertThat(dto.longitude()).isEqualTo(80.2707);
		assertThat(dto.address()).isEqualTo("Anna Salai, Chennai, Tamil Nadu, India");
		assertThat(dto.packageWeight()).isEqualTo(20);
		assertThat(dto.priority()).isEqualTo(Priority.MEDIUM);
	}

}
