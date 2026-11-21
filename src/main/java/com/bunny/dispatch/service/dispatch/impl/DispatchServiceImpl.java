package com.bunny.dispatch.service.dispatch.impl;

import com.bunny.dispatch.dto.request.OrderRequest;
import com.bunny.dispatch.dto.request.OrdersBatchRequest;
import com.bunny.dispatch.dto.request.VehicleRequest;
import com.bunny.dispatch.dto.request.VehiclesBatchRequest;
import com.bunny.dispatch.dto.response.AckResponse;
import com.bunny.dispatch.dto.response.ClearDataResponse;
import com.bunny.dispatch.dto.response.DispatchPlanResponse;
import com.bunny.dispatch.dto.response.PlanTotalsDto;
import com.bunny.dispatch.dto.response.UnassignedOrderDto;
import com.bunny.dispatch.dto.response.VehiclePlanDto;
import com.bunny.dispatch.entity.OrderEntity;
import com.bunny.dispatch.entity.VehicleEntity;
import com.bunny.dispatch.exception.DuplicateInBatchException;
import com.bunny.dispatch.exception.NoDispatchDataException;
import com.bunny.dispatch.mapper.DispatchMapper;
import com.bunny.dispatch.repository.OrderRepository;
import com.bunny.dispatch.repository.VehicleRepository;
import com.bunny.dispatch.service.dispatch.DispatchService;
import com.bunny.dispatch.service.dispatch.handler.AssignmentStrategy;
import com.bunny.dispatch.service.dispatch.handler.model.AssignmentResult;
import com.bunny.dispatch.service.dispatch.handler.model.VehicleRoute;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DispatchServiceImpl implements DispatchService {

	private final OrderRepository orderRepository;
	private final VehicleRepository vehicleRepository;
	private final DispatchMapper mapper;
	private final AssignmentStrategy assignmentStrategy;

	public DispatchServiceImpl(OrderRepository orderRepository,
			VehicleRepository vehicleRepository,
			DispatchMapper mapper,
			AssignmentStrategy assignmentStrategy) {
		this.orderRepository = orderRepository;
		this.vehicleRepository = vehicleRepository;
		this.mapper = mapper;
		this.assignmentStrategy = assignmentStrategy;
	}

	@Override
	@Transactional
	public AckResponse ingestOrders(OrdersBatchRequest batch) {
		List<OrderRequest> requests = batch.orders();
		requireUniqueKeys(requests, OrderRequest::orderId, "orderId");

		Map<String, OrderEntity> existingById = orderRepository
				.findAllById(requests.stream().map(OrderRequest::orderId).toList())
				.stream()
				.collect(Collectors.toMap(OrderEntity::getOrderId, Function.identity()));

		int inserted = 0;
		int updated = 0;
		for (OrderRequest request : requests) {
			OrderEntity managed = existingById.get(request.orderId());
			if (managed != null) {
				// load-and-copy: mutating the managed entity preserves createdAt/updatedAt;
				// saving a detached copy would merge null over createdAt
				mapper.update(request, managed);
				updated++;
			} else {
				orderRepository.save(mapper.toEntity(request));
				inserted++;
			}
		}
		return AckResponse.orders(requests.size(), inserted, updated);
	}

	@Override
	@Transactional
	public AckResponse ingestVehicles(VehiclesBatchRequest batch) {
		List<VehicleRequest> requests = batch.vehicles();
		requireUniqueKeys(requests, VehicleRequest::vehicleId, "vehicleId");

		Map<String, VehicleEntity> existingById = vehicleRepository
				.findAllById(requests.stream().map(VehicleRequest::vehicleId).toList())
				.stream()
				.collect(Collectors.toMap(VehicleEntity::getVehicleId, Function.identity()));

		int inserted = 0;
		int updated = 0;
		for (VehicleRequest request : requests) {
			VehicleEntity managed = existingById.get(request.vehicleId());
			if (managed != null) {
				mapper.update(request, managed);
				updated++;
			} else {
				vehicleRepository.save(mapper.toEntity(request));
				inserted++;
			}
		}
		return AckResponse.vehicles(requests.size(), inserted, updated);
	}

	@Override
	@Transactional(readOnly = true)
	public DispatchPlanResponse buildPlan() {
		List<OrderEntity> orders = orderRepository.findAll();
		List<VehicleEntity> vehicles = vehicleRepository.findAll();
		if (orders.isEmpty() && vehicles.isEmpty()) {
			throw new NoDispatchDataException("No dispatch data available. Post orders and vehicles first.");
		}

		AssignmentResult result = assignmentStrategy.assign(
				orders.stream().map(mapper::toPlanned).toList(),
				vehicles.stream().map(mapper::toPlanned).toList());

		List<VehiclePlanDto> dispatchPlan = result.routes().stream()
				.map(this::toVehiclePlanDto)
				.toList();
		List<UnassignedOrderDto> unassigned = result.unassigned().stream()
				.map(u -> UnassignedOrderDto.of(u.order().orderId(), u.order().packageWeight(),
						u.order().priority(), u.reason()))
				.toList();

		double totalDistanceKm = result.routes().stream()
				.mapToDouble(VehicleRoute::totalDistanceKm)
				.sum();
		PlanTotalsDto totals = new PlanTotalsDto(
				result.routes().stream().mapToInt(r -> r.orderedStops().size()).sum(),
				unassigned.size(),
				result.routes().stream().mapToInt(VehicleRoute::totalLoad).sum(),
				totalDistanceKm,
				VehiclePlanDto.formatKm(totalDistanceKm));
		return new DispatchPlanResponse(dispatchPlan, unassigned, totals);
	}

	@Override
	@Transactional
	public ClearDataResponse clearData() {
		long orders = orderRepository.count();
		long vehicles = vehicleRepository.count();
		orderRepository.deleteAllInBatch();
		vehicleRepository.deleteAllInBatch();
		return ClearDataResponse.of(orders, vehicles);
	}

	private VehiclePlanDto toVehiclePlanDto(VehicleRoute route) {
		return new VehiclePlanDto(
				route.vehicle().vehicleId(),
				route.vehicle().currentAddress(),
				route.totalLoad(),
				VehiclePlanDto.formatKm(route.totalDistanceKm()),
				route.totalDistanceKm(),
				route.legs().stream().map(mapper::toDto).toList(),
				route.orderedStops().stream().map(s -> mapper.toDto(s.order())).toList());
	}

	private static <T> void requireUniqueKeys(List<T> batch, Function<T, String> keyGetter, String keyName) {
		Set<String> seen = new LinkedHashSet<>();
		for (T item : batch) {
			String key = keyGetter.apply(item);
			if (!seen.add(key)) {
				throw new DuplicateInBatchException("Duplicate " + keyName + " in request: " + key);
			}
		}
	}

}
