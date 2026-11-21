package com.bunny.dispatch.mapper;

import com.bunny.dispatch.dto.request.OrderRequest;
import com.bunny.dispatch.dto.request.VehicleRequest;
import com.bunny.dispatch.dto.response.AssignedOrderDto;
import com.bunny.dispatch.dto.response.RouteLegDto;
import com.bunny.dispatch.entity.OrderEntity;
import com.bunny.dispatch.entity.VehicleEntity;
import com.bunny.dispatch.service.dispatch.handler.model.PlannedOrder;
import com.bunny.dispatch.service.dispatch.handler.model.PlannedVehicle;
import com.bunny.dispatch.service.dispatch.handler.model.RouteLeg;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

/**
 * All DTO/entity/algorithm-model conversions live here. {@code unmappedTargetPolicy = ERROR}
 * makes a new entity column without a matching mapping a build failure instead of a silent null.
 * createdAt/updatedAt are DB-managed ({@code @PrePersist}/{@code @PreUpdate}) and never mapped
 * from requests — the update methods in particular must not touch them, which is what keeps
 * load-and-copy upserts from erasing an order's original timestamp.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface DispatchMapper {

	@Mapping(target = "location.latitude", source = "latitude")
	@Mapping(target = "location.longitude", source = "longitude")
	@Mapping(target = "createdAt", ignore = true)
	@Mapping(target = "updatedAt", ignore = true)
	OrderEntity toEntity(OrderRequest request);

	@Mapping(target = "location.latitude", source = "latitude")
	@Mapping(target = "location.longitude", source = "longitude")
	@Mapping(target = "createdAt", ignore = true)
	@Mapping(target = "updatedAt", ignore = true)
	void update(OrderRequest request, @MappingTarget OrderEntity target);

	@Mapping(target = "currentPoint.latitude", source = "currentLatitude")
	@Mapping(target = "currentPoint.longitude", source = "currentLongitude")
	@Mapping(target = "createdAt", ignore = true)
	@Mapping(target = "updatedAt", ignore = true)
	VehicleEntity toEntity(VehicleRequest request);

	@Mapping(target = "currentPoint.latitude", source = "currentLatitude")
	@Mapping(target = "currentPoint.longitude", source = "currentLongitude")
	@Mapping(target = "createdAt", ignore = true)
	@Mapping(target = "updatedAt", ignore = true)
	void update(VehicleRequest request, @MappingTarget VehicleEntity target);

	@Mapping(target = "latitude", source = "location.latitude")
	@Mapping(target = "longitude", source = "location.longitude")
	PlannedOrder toPlanned(OrderEntity entity);

	@Mapping(target = "currentLatitude", source = "currentPoint.latitude")
	@Mapping(target = "currentLongitude", source = "currentPoint.longitude")
	PlannedVehicle toPlanned(VehicleEntity entity);

	AssignedOrderDto toDto(PlannedOrder order);

	RouteLegDto toDto(RouteLeg leg);

}
