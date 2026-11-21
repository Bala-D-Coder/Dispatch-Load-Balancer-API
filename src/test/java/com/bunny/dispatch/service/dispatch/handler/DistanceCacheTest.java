package com.bunny.dispatch.service.dispatch.handler;

import com.bunny.dispatch.util.DistanceUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.withinPercentage;

/**
 * Golden pairs come from throwaway Part 1 / Part 4 preflights (a Java one-shot and
 * a leg-table probe, both R=6371.0, each cross-checked against an independent Node.js
 * implementation to 6 dp).
 */
class DistanceCacheTest {

	private static final double CP_LAT = 28.6139;
	private static final double CP_LON = 77.2090;

	// lat1, lon1, lat2, lon2, golden km — the 9 preflight pairs
	private static final double[][] PREFLIGHT_PAIRS = {
			{13.0827, 80.2707, 12.9716, 77.5946, 290.172025},
			{28.6139, 77.2090, 28.7041, 77.1025, 14.442261},
			{28.6139, 77.2090, 28.5355, 77.3910, 19.795386},
			{28.6139, 77.2090, 28.4595, 77.0266, 24.743390},
			{28.7041, 77.1025, 28.5355, 77.3910, 33.829872},
			{28.7041, 77.1025, 28.4595, 77.0266, 28.189927},
			{28.5355, 77.3910, 28.4595, 77.0266, 36.599016},
			{0.0, 0.0, 0.0, 1.0, 111.194927},
			{0.0, 179.99, 0.0, -179.99, 2.223899},
	};

	@Test
	void cachedEqualsDirect() {
		DistanceCache cache = new DistanceCache();
		for (double[] pair : PREFLIGHT_PAIRS) {
			double cached = cache.km(pair[0], pair[1], pair[2], pair[3]);
			double direct = DistanceUtils.haversineKm(pair[0], pair[1], pair[2], pair[3]);
			assertThat(cached).isCloseTo(direct, within(1e-12));
			assertThat(cached).isCloseTo(pair[4], withinPercentage(0.5));
		}
	}

	@Test
	void repeatedPairIncrementsHits() {
		DistanceCache cache = new DistanceCache();
		double first = cache.km(CP_LAT, CP_LON, 28.7041, 77.1025);
		double second = cache.km(CP_LAT, CP_LON, 28.7041, 77.1025);
		double third = cache.km(CP_LAT, CP_LON, 28.7041, 77.1025);
		assertThat(second).isEqualTo(first);
		assertThat(third).isEqualTo(first);
		assertThat(cache.hits()).isEqualTo(2);
	}

	@Test
	void identicalCoordClusterAllZero() {
		DistanceCache cache = new DistanceCache();
		for (int i = 0; i < 10; i++) {
			assertThat(cache.km(CP_LAT, CP_LON, CP_LAT, CP_LON)).isEqualTo(0.0);
		}
		assertThat(cache.hits()).isEqualTo(9);
	}

	// The key used to be the four coordinates rendered with "%.6f", so any two points inside the
	// same ~1.1 cm cell shared one entry and the second query was answered with the first pair's
	// distance. A cache may only memoise a value, never approximate its inputs.
	@Test
	void nearbyButDistinctPointsNeverShareAnEntry() {
		DistanceCache cache = new DistanceCache();
		double same = cache.km(CP_LAT, CP_LON, CP_LAT, CP_LON);
		// 1e-7 degrees is ~1.1 cm — both round to 28.613900 at 6 dp
		double shifted = cache.km(CP_LAT, CP_LON, CP_LAT + 1e-7, CP_LON);
		double far = cache.km(CP_LAT, CP_LON, CP_LAT + 1e-5, CP_LON);

		assertThat(same).isZero();
		assertThat(shifted).isGreaterThan(0.0);
		assertThat(far).isGreaterThan(shifted);
		assertThat(cache.hits()).isZero();
	}

	@Test
	void exactRepeatsAreServedFromTheEntry() {
		DistanceCache cache = new DistanceCache();
		double first = cache.km(CP_LAT, CP_LON, 28.7041, 77.1025);
		// same four double values, different expression of them: still one entry
		double second = cache.km(CP_LAT, CP_LON + 0.0, 28.7041, 77.1025 * 1.0);
		assertThat(second).isEqualTo(first);
		assertThat(cache.hits()).isEqualTo(1);
	}

}
