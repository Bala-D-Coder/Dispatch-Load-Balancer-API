package com.bunny.dispatch.service.dispatch.handler;

import com.bunny.dispatch.entity.common.Priority;
import com.bunny.dispatch.service.dispatch.handler.model.PlannedOrder;
import com.bunny.dispatch.service.dispatch.handler.model.PlannedVehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Shared Part 4 test data. The Delhi fixture is transcribed verbatim from requirements.txt
 * (sample inputs): 30 orders totalling 645 kg against 5 vehicles totalling 500 kg capacity.
 */
final class DispatchFixtures {

	static final double CP_LAT = 28.6139;
	static final double CP_LON = 77.2090;
	static final double KB_LAT = 28.7041;
	static final double KB_LON = 77.1025;
	static final double NOIDA_LAT = 28.5355;
	static final double NOIDA_LON = 77.3910;
	static final double GURGAON_LAT = 28.4595;
	static final double GURGAON_LON = 77.0266;

	private static final String CP = "Connaught Place, Delhi, India";
	private static final String KAROL_BAGH = "Karol Bagh, Delhi, India";
	private static final String NOIDA = "Sector 18, Noida, Uttar Pradesh, India";
	private static final String GURGAON = "Cyber Hub, Gurgaon, Haryana, India";

	record Dataset(List<PlannedOrder> orders, List<PlannedVehicle> vehicles) {
	}

	private DispatchFixtures() {
	}

	static PlannedOrder order(String id, double lat, double lon, String address, int weight, Priority priority) {
		return new PlannedOrder(id, lat, lon, address, weight, priority);
	}

	static List<PlannedOrder> delhiOrders() {
		return List.of(
				order("ORD001", CP_LAT, CP_LON, CP, 15, Priority.HIGH),
				order("ORD002", CP_LAT, CP_LON, CP, 10, Priority.MEDIUM),
				order("ORD003", KB_LAT, KB_LON, KAROL_BAGH, 20, Priority.LOW),
				order("ORD004", NOIDA_LAT, NOIDA_LON, NOIDA, 25, Priority.HIGH),
				order("ORD005", GURGAON_LAT, GURGAON_LON, GURGAON, 30, Priority.MEDIUM),
				order("ORD006", CP_LAT, CP_LON, CP, 40, Priority.LOW),
				order("ORD007", KB_LAT, KB_LON, KAROL_BAGH, 20, Priority.HIGH),
				order("ORD008", NOIDA_LAT, NOIDA_LON, NOIDA, 25, Priority.HIGH),
				order("ORD009", GURGAON_LAT, GURGAON_LON, GURGAON, 15, Priority.MEDIUM),
				order("ORD010", CP_LAT, CP_LON, CP, 30, Priority.LOW),
				order("ORD011", KB_LAT, KB_LON, KAROL_BAGH, 20, Priority.HIGH),
				order("ORD012", NOIDA_LAT, NOIDA_LON, NOIDA, 10, Priority.MEDIUM),
				order("ORD013", GURGAON_LAT, GURGAON_LON, GURGAON, 25, Priority.HIGH),
				order("ORD014", CP_LAT, CP_LON, CP, 15, Priority.LOW),
				order("ORD015", KB_LAT, KB_LON, KAROL_BAGH, 20, Priority.HIGH),
				order("ORD016", NOIDA_LAT, NOIDA_LON, NOIDA, 30, Priority.LOW),
				order("ORD017", GURGAON_LAT, GURGAON_LON, GURGAON, 15, Priority.MEDIUM),
				order("ORD018", CP_LAT, CP_LON, CP, 10, Priority.HIGH),
				order("ORD019", KB_LAT, KB_LON, KAROL_BAGH, 20, Priority.LOW),
				order("ORD020", NOIDA_LAT, NOIDA_LON, NOIDA, 30, Priority.HIGH),
				order("ORD021", CP_LAT, CP_LON, CP, 25, Priority.MEDIUM),
				order("ORD022", KB_LAT, KB_LON, KAROL_BAGH, 20, Priority.LOW),
				order("ORD023", NOIDA_LAT, NOIDA_LON, NOIDA, 30, Priority.HIGH),
				order("ORD024", GURGAON_LAT, GURGAON_LON, GURGAON, 15, Priority.LOW),
				order("ORD025", CP_LAT, CP_LON, CP, 20, Priority.MEDIUM),
				order("ORD026", KB_LAT, KB_LON, KAROL_BAGH, 25, Priority.HIGH),
				order("ORD027", NOIDA_LAT, NOIDA_LON, NOIDA, 20, Priority.MEDIUM),
				order("ORD028", GURGAON_LAT, GURGAON_LON, GURGAON, 15, Priority.LOW),
				order("ORD029", CP_LAT, CP_LON, CP, 30, Priority.HIGH),
				order("ORD030", KB_LAT, KB_LON, KAROL_BAGH, 20, Priority.LOW));
	}

	static List<PlannedVehicle> delhiVehicles() {
		return List.of(
				new PlannedVehicle("VEH001", 100, KB_LAT, KB_LON, KAROL_BAGH),
				new PlannedVehicle("VEH002", 80, NOIDA_LAT, NOIDA_LON, NOIDA),
				new PlannedVehicle("VEH003", 120, GURGAON_LAT, GURGAON_LON, GURGAON),
				new PlannedVehicle("VEH004", 90, CP_LAT, CP_LON, CP),
				new PlannedVehicle("VEH005", 110, KB_LAT, KB_LON, KAROL_BAGH));
	}

	/** Deterministic pseudo-random datasets for the property tests — same seed, same data. */
	static List<Dataset> seededDatasets(long seed, int count) {
		Random random = new Random(seed);
		double[] grid = {28.4000, 28.4500, 28.5000, 28.5355, 28.6000, 28.6139, 28.7000, 28.7041};
		List<Dataset> datasets = new ArrayList<>();
		for (int d = 0; d < count; d++) {
			int orderCount = 1 + random.nextInt(40);
			List<PlannedOrder> orders = new ArrayList<>();
			for (int i = 0; i < orderCount; i++) {
				Priority priority = Priority.values()[random.nextInt(3)];
				int weight = 5 + random.nextInt(46);
				orders.add(order("PORD%03d".formatted(i), grid[random.nextInt(grid.length)],
						grid[random.nextInt(grid.length)], "Grid stop " + i, weight, priority));
			}
			int vehicleCount = 1 + random.nextInt(8);
			List<PlannedVehicle> vehicles = new ArrayList<>();
			for (int i = 0; i < vehicleCount; i++) {
				int capacity = 40 + random.nextInt(7) * 40;
				vehicles.add(new PlannedVehicle("PVEH%02d".formatted(i), capacity,
						grid[random.nextInt(grid.length)], grid[random.nextInt(grid.length)],
						"Depot " + i));
			}
			datasets.add(new Dataset(orders, vehicles));
		}
		return datasets;
	}

}
