package com.bunny.dispatch.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.withinPercentage;

/**
 * Golden values derived 2026-11-20 by a throwaway Java preflight (R=6371.0),
 * cross-checked against an independent Node.js implementation (identical to 6 dp) and
 * against the analytic equator anchor 2*PI*6371/360 = 111.194927 km.
 */
class DistanceUtilsTest {

	// Anna Salai, Chennai -> MG Road, Bangalore (spec sample pair)
	private static final double CHENNAI_BANGALORE = 290.172025;
	// Connaught Place -> Karol Bagh (Delhi sample cluster leg)
	private static final double CP_KAROL_BAGH = 14.442261;
	private static final double CP_NOIDA = 19.795386;
	private static final double CP_GURGAON = 24.743390;

	@Test
	void samePointIsExactlyZero() {
		assertThat(DistanceUtils.haversineKm(28.6139, 77.2090, 28.6139, 77.2090)).isEqualTo(0.0);
	}

	@Test
	void symmetricForAllSamplePairs() {
		assertSymmetric(13.0827, 80.2707, 12.9716, 77.5946);
		assertSymmetric(28.6139, 77.2090, 28.7041, 77.1025);
		assertSymmetric(0.0, 179.99, 0.0, -179.99);
		assertSymmetric(89.9, 0.0, -89.9, 180.0);
	}

	@Test
	void chennaiToBangaloreMatchesGolden() {
		assertThat(DistanceUtils.haversineKm(13.0827, 80.2707, 12.9716, 77.5946))
				.isCloseTo(CHENNAI_BANGALORE, withinPercentage(0.5));
	}

	@Test
	void cpToKarolBaghMatchesGolden() {
		assertThat(DistanceUtils.haversineKm(28.6139, 77.2090, 28.7041, 77.1025))
				.isCloseTo(CP_KAROL_BAGH, withinPercentage(0.5));
	}

	@Test
	void cpToNoidaAndGurgaonMatchGolden() {
		assertThat(DistanceUtils.haversineKm(28.6139, 77.2090, 28.5355, 77.3910))
				.isCloseTo(CP_NOIDA, withinPercentage(0.5));
		assertThat(DistanceUtils.haversineKm(28.6139, 77.2090, 28.4595, 77.0266))
				.isCloseTo(CP_GURGAON, withinPercentage(0.5));
	}

	@Test
	void oneDegreeOnEquatorIsAbout111Km() {
		assertThat(DistanceUtils.haversineKm(0, 0, 0, 1)).isCloseTo(111.194927, within(0.1));
	}

	@Test
	void antimeridianPairIsShortNotWrapAround() {
		double d = DistanceUtils.haversineKm(0, 179.99, 0, -179.99);
		assertThat(d).isCloseTo(2.223899, withinPercentage(0.5));
		assertThat(d).isLessThan(10.0);
	}

	@Test
	void nearAntipodalWithinHalfPercentOfGolden() {
		assertThat(DistanceUtils.haversineKm(89.9, 0, -89.9, 180))
				.isCloseTo(20015.086796, withinPercentage(0.5));
	}

	/**
	 * `Math.asin` has domain [-1, 1] and the sum-of-squares `a` term can round just past it. Swept in
	 * Java over the validated coordinate box: 58,000,029 points on the antipodal curve plus 38,571,170
	 * pole-biased random pairs gave a worst `a` of 1 + 1 ulp (1.0000000000000002) — and
	 * {@code Math.sqrt} of that is exactly 1.0 under round-half-to-even, so 0 NaN results in 96.5M cases.
	 * Nothing is clamped because nothing overflows; this test is what keeps that a fact rather than an
	 * assumption if the formula, the radius or the JDK's rounding is ever touched.
	 */
	@Test
	void worstMeasuredAntipodalInputIsFiniteAndCappedAtHalfTheCircumference() {
		double halfCircumference = Math.PI * 6371.0;
		double worst = DistanceUtils.haversineKm(89.95645055781685, 0.1, -89.95645030639342, -179.90000007024415);
		assertThat(worst).isFinite();
		assertThat(worst).isCloseTo(20015.086796021, within(1e-6));
		assertThat(worst).isCloseTo(halfCircumference, within(1e-9));
		// the pair whose `a` first crossed 1.0 in the sweep
		assertThat(DistanceUtils.haversineKm(-89.99208, 0.0, 89.99208, 180.0)).isFinite();
	}

	@Test
	void negativeCoordinatesBehaveLikePositives() {
		double positive = DistanceUtils.haversineKm(13.0827, 80.2707, 12.9716, 77.5946);
		double negative = DistanceUtils.haversineKm(-13.0827, -80.2707, -12.9716, -77.5946);
		assertThat(negative).isCloseTo(positive, within(1e-9));
	}

	@Test
	void monotonicInLatitudeSeparation() {
		double near = DistanceUtils.haversineKm(0, 0, 10, 0);
		double mid = DistanceUtils.haversineKm(0, 0, 20, 0);
		double far = DistanceUtils.haversineKm(0, 0, 30, 0);
		assertThat(near).isLessThan(mid);
		assertThat(mid).isLessThan(far);
	}

	private static void assertSymmetric(double lat1, double lon1, double lat2, double lon2) {
		assertThat(DistanceUtils.haversineKm(lat1, lon1, lat2, lon2))
				.isCloseTo(DistanceUtils.haversineKm(lat2, lon2, lat1, lon1), within(1e-9));
	}

}
