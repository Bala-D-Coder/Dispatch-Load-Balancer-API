package com.bunny.dispatch.service.dispatch.handler;

import com.bunny.dispatch.entity.common.UnassignedReason;
import com.bunny.dispatch.service.dispatch.handler.model.AssignmentResult;
import com.bunny.dispatch.service.dispatch.handler.model.PlannedOrder;
import com.bunny.dispatch.service.dispatch.handler.model.PlannedVehicle;
import com.bunny.dispatch.service.dispatch.handler.model.UnassignedOrder;
import com.bunny.dispatch.service.dispatch.handler.model.VehicleRoute;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Priority-first greedy assignment (D13): orders are processed in
 * {@link OrderSorter} order; each goes to the nearest feasible vehicle measured from that
 * vehicle's current route tip. Tie-breaks: larger remaining capacity, then lower vehicleId.
 * Vehicles are always iterated in vehicleId order so results are deterministic.
 *
 * <p>Only vehicles that received at least one stop appear in {@code routes} — idle vehicles
 * would add noise to the dispatch plan (matches the spec sample).
 */
public class GreedyAssignmentStrategy implements AssignmentStrategy {

	private final RoutePlanner routePlanner = new RoutePlanner();

	@Override
	public AssignmentResult assign(List<PlannedOrder> orders, List<PlannedVehicle> vehicles) {
		List<VehicleSlot> slots = vehicles.stream()
				.sorted(Comparator.comparing(PlannedVehicle::vehicleId))
				.map(VehicleSlot::new)
				.toList();
		int maxCapacity = slots.stream().mapToInt(s -> s.vehicle.capacity()).max().orElse(0);

		DistanceCache cache = new DistanceCache();
		List<UnassignedOrder> unassigned = new ArrayList<>();

		for (PlannedOrder order : OrderSorter.sorted(orders)) {
			if (slots.isEmpty()) {
				unassigned.add(new UnassignedOrder(order, UnassignedReason.NO_VEHICLES_AVAILABLE));
				continue;
			}
			VehicleSlot best = null;
			double bestKm = 0.0;
			for (VehicleSlot slot : slots) {
				if (slot.remaining < order.packageWeight()) {
					continue;
				}
				double km = cache.km(slot.tipLat, slot.tipLon, order.latitude(), order.longitude());
				if (best == null || km < bestKm
						|| (km == bestKm && slot.remaining > best.remaining)) {
					best = slot;
					bestKm = km;
				}
			}
			if (best == null) {
				unassigned.add(new UnassignedOrder(order, order.packageWeight() > maxCapacity
						? UnassignedReason.EXCEEDS_ALL_VEHICLE_CAPACITIES
						: UnassignedReason.NO_VEHICLE_WITH_SUFFICIENT_REMAINING_CAPACITY));
				continue;
			}
			best.remaining -= order.packageWeight();
			best.tipLat = order.latitude();
			best.tipLon = order.longitude();
			best.stops.add(order);
		}

		List<VehicleRoute> routes = new ArrayList<>();
		for (VehicleSlot slot : slots) {
			if (!slot.stops.isEmpty()) {
				routes.add(routePlanner.plan(slot.vehicle, slot.stops, cache));
			}
		}
		return new AssignmentResult(List.copyOf(routes), List.copyOf(unassigned));
	}

	private static final class VehicleSlot {

		private final PlannedVehicle vehicle;
		private final List<PlannedOrder> stops = new ArrayList<>();
		private int remaining;
		private double tipLat;
		private double tipLon;

		private VehicleSlot(PlannedVehicle vehicle) {
			this.vehicle = vehicle;
			this.remaining = vehicle.capacity();
			this.tipLat = vehicle.currentLatitude();
			this.tipLon = vehicle.currentLongitude();
		}

	}

}
