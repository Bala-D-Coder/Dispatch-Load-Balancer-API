package com.bunny.dispatch;

import com.bunny.dispatch.dto.response.DispatchPlanResponse;
import com.bunny.dispatch.dto.response.VehiclePlanDto;
import com.bunny.dispatch.entity.common.Priority;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The requirement's "Performance — efficient handling of large datasets", measured rather than
 * claimed: 5,000 orders against a 20-vehicle fleet, plan built three times, median asserted
 * under the 2,000 ms fence and printed so the README can quote the real number.
 *
 * <p>Ingestion here is a JDBC batch, not the HTTP endpoint: this test measures plan BUILDING,
 * which is what recomputes on every GET. The endpoint's per-record path is covered by the
 * validation and golden tests at realistic batch sizes.
 *
 * <p>Coordinates repeat across a small grid on purpose — that is the designed path for
 * {@code DistanceCache}. Worst case (all-distinct coordinates) is n·m = 100,000 Haversine
 * computations, a few ms of pure arithmetic, so the bound holds either way.
 */
class DispatchPerformanceIT extends AbstractE2ETest {

	private static final Logger log = LoggerFactory.getLogger(DispatchPerformanceIT.class);

	private static final int ORDER_COUNT = 5_000;
	private static final int VEHICLE_COUNT = 20;
	/** Generous regression fence, not a target — the measurement itself is the deliverable. */
	private static final long MEDIAN_BUDGET_MS = 2_000;
	private static final double[] GRID = {28.4000, 28.4500, 28.5000, 28.5355, 28.6000, 28.6139, 28.7000, 28.7041};
	private static final int[] VEHICLE_CAPACITIES = buildCapacities();

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void buildPlanForFiveThousandOrdersStaysWithinBudget() {
		long ingestStart = System.nanoTime();
		insertBulkRows();
		log.info("PERF ingest: {} ms for {} orders / {} vehicles",
				(System.nanoTime() - ingestStart) / 1_000_000, ORDER_COUNT, VEHICLE_COUNT);

		// un-timed: JIT warm-up, Hibernate statement plan cache, pool connection setup
		service.buildPlan();

		List<Long> elapsedMs = new ArrayList<>();
		for (int run = 0; run < 3; run++) {
			long start = System.nanoTime();
			DispatchPlanResponse plan = service.buildPlan();
			elapsedMs.add((System.nanoTime() - start) / 1_000_000);
			assertThat(plan.totals().assignedOrderCount() + plan.totals().unassignedOrderCount())
					.as("every order is accounted for, run " + run).isEqualTo(ORDER_COUNT);
		}

		long median = median(elapsedMs);
		log.info("PERF buildPlan: median {} ms (runs: {}) for {} orders / {} vehicles",
				median, elapsedMs, ORDER_COUNT, VEHICLE_COUNT);
		assertThat(median).isLessThan(MEDIAN_BUDGET_MS);
	}

	@Test
	void capacityInvariantStillHoldsAtScale() {
		insertBulkRows();

		DispatchPlanResponse plan = service.buildPlan();
		for (VehiclePlanDto vehicle : plan.dispatchPlan()) {
			assertThat(vehicle.totalLoad())
					.as("vehicle %s over capacity", vehicle.vehicleId())
					.isLessThanOrEqualTo(capacity(vehicle.vehicleId()));
		}
		// the fleet is smaller than the demand, so overflow must land in unassigned, not vanish
		assertThat(plan.totals().unassignedOrderCount()).isPositive();
		assertThat(plan.dispatchPlan()).isNotEmpty();
	}

	private static int[] buildCapacities() {
		int[] capacities = new int[VEHICLE_COUNT];
		Random random = new Random(42);
		for (int i = 0; i < VEHICLE_COUNT; i++) {
			capacities[i] = 800 + random.nextInt(5) * 100;
		}
		return capacities;
	}

	private static int capacity(String vehicleId) {
		return VEHICLE_CAPACITIES[Integer.parseInt(vehicleId.substring(4))];
	}

	private void insertBulkRows() {
		Random random = new Random(42);
		Priority[] priorities = Priority.values();
		List<Object[]> orderRows = new ArrayList<>(ORDER_COUNT);
		for (int i = 0; i < ORDER_COUNT; i++) {
			orderRows.add(new Object[]{
					"PORD%04d".formatted(i),
					GRID[random.nextInt(GRID.length)],
					GRID[random.nextInt(GRID.length)],
					"Grid stop " + (i % 64) + ", Delhi, India",
					5 + random.nextInt(46),
					priorities[random.nextInt(priorities.length)].name()});
		}
		jdbcTemplate.batchUpdate(
				"INSERT INTO orders (order_id, latitude, longitude, address, package_weight, priority, created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, ?, ?, now(), now())", orderRows);

		List<Object[]> vehicleRows = new ArrayList<>(VEHICLE_COUNT);
		for (int i = 0; i < VEHICLE_COUNT; i++) {
			vehicleRows.add(new Object[]{
					"PVEH%04d".formatted(i),
					VEHICLE_CAPACITIES[i],
					GRID[random.nextInt(GRID.length)],
					GRID[random.nextInt(GRID.length)],
					"Depot " + i + ", Delhi, India"});
		}
		jdbcTemplate.batchUpdate(
				"INSERT INTO vehicles (vehicle_id, capacity, current_latitude, current_longitude, current_address, created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, ?, now(), now())", vehicleRows);
	}

	private static long median(List<Long> values) {
		List<Long> sorted = values.stream().sorted().toList();
		return sorted.get(sorted.size() / 2);
	}

}
