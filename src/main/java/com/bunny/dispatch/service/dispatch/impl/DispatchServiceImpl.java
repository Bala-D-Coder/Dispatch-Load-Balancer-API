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
import com.bunny.dispatch.service.dispatch.handler.model.PlannedOrder;
import com.bunny.dispatch.service.dispatch.handler.model.PlannedVehicle;
import com.bunny.dispatch.service.dispatch.handler.model.VehicleRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestration only — selection and routing live in {@code service/dispatch/handler/}, which
 * deliberately holds no Spring or logging dependency. The one INFO line per call below is
 * therefore the finest phase detail the deployed logs can give: the algorithm is timed as a
 * single block from here, not instrumented from inside.
 */
@Service
public class DispatchServiceImpl implements DispatchService {

	private static final Logger log = LoggerFactory.getLogger(DispatchServiceImpl.class);

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
		long started = System.nanoTime();
		requireUniqueKeys(requests, OrderRequest::orderId, "orderId");
		double validateMs = ms(System.nanoTime() - started);

		long upsertStarted = System.nanoTime();
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
		log.info("ingestOrders received={} inserted={} updated={} validateMs={} upsertMs={}",
				requests.size(), inserted, updated, validateMs, ms(System.nanoTime() - upsertStarted));
		return AckResponse.orders(requests.size(), inserted, updated);
	}

	@Override
	@Transactional
	public AckResponse ingestVehicles(VehiclesBatchRequest batch) {
		List<VehicleRequest> requests = batch.vehicles();
		long started = System.nanoTime();
		requireUniqueKeys(requests, VehicleRequest::vehicleId, "vehicleId");
		double validateMs = ms(System.nanoTime() - started);

		long upsertStarted = System.nanoTime();
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
		log.info("ingestVehicles received={} inserted={} updated={} validateMs={} upsertMs={}",
				requests.size(), inserted, updated, validateMs, ms(System.nanoTime() - upsertStarted));
		return AckResponse.vehicles(requests.size(), inserted, updated);
	}

	@Override
	@Transactional(readOnly = true)
	public DispatchPlanResponse buildPlan() {
		long loadOrdersStarted = System.nanoTime();
		List<OrderEntity> orders = orderRepository.findAll();
		double loadOrdersMs = ms(System.nanoTime() - loadOrdersStarted);

		long loadVehiclesStarted = System.nanoTime();
		List<VehicleEntity> vehicles = vehicleRepository.findAll();
		double loadVehiclesMs = ms(System.nanoTime() - loadVehiclesStarted);

		if (orders.isEmpty() && vehicles.isEmpty()) {
			throw new NoDispatchDataException("No dispatch data available. Post orders and vehicles first.");
		}

		// mapping is split from selection/routing because the two have opposite costs: mapping is
		// proportional to rows, assignment to rows x vehicles. Summed they would hide which one grew.
		long mapNanos = 0;
		long mapStarted = System.nanoTime();
		List<PlannedOrder> plannedOrders = orders.stream().map(mapper::toPlanned).toList();
		List<PlannedVehicle> plannedVehicles = vehicles.stream().map(mapper::toPlanned).toList();
		mapNanos += System.nanoTime() - mapStarted;

		long assignStarted = System.nanoTime();
		AssignmentResult result = assignmentStrategy.assign(plannedOrders, plannedVehicles);
		double assignAndRouteMs = ms(System.nanoTime() - assignStarted);

		mapStarted = System.nanoTime();
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
		mapNanos += System.nanoTime() - mapStarted;

		log.info("buildPlan orders={} vehicles={} assigned={} unassigned={} totalLoadKg={} totalDistanceKm={}"
						+ " loadOrdersMs={} loadVehiclesMs={} assignAndRouteMs={} mapMs={}",
				orders.size(), vehicles.size(), totals.assignedOrderCount(), unassigned.size(),
				totals.totalLoadKg(), totalDistanceKm,
				loadOrdersMs, loadVehiclesMs, assignAndRouteMs, ms((long) mapNanos));
		return new DispatchPlanResponse(dispatchPlan, unassigned, totals);
	}

	@Override
	@Transactional
	public ClearDataResponse clearData() {
		long started = System.nanoTime();
		long orders = orderRepository.count();
		long vehicles = vehicleRepository.count();
		orderRepository.deleteAllInBatch();
		vehicleRepository.deleteAllInBatch();
		log.info("clearData deletedOrders={} deletedVehicles={} deleteMs={}",
				orders, vehicles, ms(System.nanoTime() - started));
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

	/** Two decimals: enough to separate a 0.02 ms in-memory phase from a 40 ms query, cheap enough to always call. */
	private static double ms(long nanos) {
		return Math.round(nanos / 10_000) / 100.0;
	}

}
