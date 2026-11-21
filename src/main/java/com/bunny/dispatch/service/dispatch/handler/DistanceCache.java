package com.bunny.dispatch.service.dispatch.handler;

import com.bunny.dispatch.util.DistanceUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Memoises Haversine results per coordinate quadruple.
 *
 * <p>The key is the four IEEE-754 bit patterns. It used to be those coordinates rendered with
 * {@code "%.6f|…"}, which was both slower than the arithmetic it guarded (~1 µs of
 * {@code String.format} against ~50 ns of trig, measured) and lossy: two distinct points inside the
 * same 1.1 cm cell shared one entry, so the second query was answered with the first pair's
 * distance. Exact bits cost nothing to build and cannot alias.
 */
public class DistanceCache {

	private final Map<Quadruple, Double> cache = new HashMap<>();
	private long hits;

	public double km(double lat1, double lon1, double lat2, double lon2) {
		Quadruple key = new Quadruple(Double.doubleToRawLongBits(lat1), Double.doubleToRawLongBits(lon1),
				Double.doubleToRawLongBits(lat2), Double.doubleToRawLongBits(lon2));
		Double cached = cache.get(key);
		if (cached != null) {
			hits++;
			return cached;
		}
		double value = DistanceUtils.haversineKm(lat1, lon1, lat2, lon2);
		cache.put(key, value);
		return value;
	}

	public long hits() {
		return hits;
	}

	// Coordinates are validated finite doubles, so raw bits suffice — no NaN canonicalisation needed.
	private record Quadruple(long lat1, long lon1, long lat2, long lon2) {
	}

}
