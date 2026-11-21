package com.bunny.dispatch.util;

public final class DistanceUtils {

	private static final double EARTH_RADIUS_KM = 6371.0;

	private DistanceUtils() {
	}

	public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
		double dLat = Math.toRadians(lat2 - lat1);
		double dLon = Math.toRadians(lon2 - lon1);
		double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
				+ Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
				* Math.sin(dLon / 2) * Math.sin(dLon / 2);
		// a can round to 1 + 1 ulp for near-antipodal input; sqrt maps that back to exactly 1.0, so no
		// clamp is needed — pinned by DistanceUtilsTest.worstMeasuredAntipodalInputIsFiniteAndCappedAtHalfTheCircumference
		return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
	}

}
