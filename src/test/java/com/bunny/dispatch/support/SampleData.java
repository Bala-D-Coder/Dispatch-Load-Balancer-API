package com.bunny.dispatch.support;

import com.bunny.dispatch.dto.request.OrderRequest;
import com.bunny.dispatch.dto.request.OrdersBatchRequest;
import com.bunny.dispatch.dto.request.VehicleRequest;
import com.bunny.dispatch.dto.request.VehiclesBatchRequest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Loads the Part 7 sample JSON files (third transcription of requirements.txt) and
 * serializes request DTOs for MockMvc posts.
 */
public final class SampleData {

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private SampleData() {
	}

	public static OrdersBatchRequest orders(String name) {
		return read("sample/" + name, OrdersBatchRequest.class);
	}

	public static VehiclesBatchRequest vehicles(String name) {
		return read("sample/" + name, VehiclesBatchRequest.class);
	}

	/** Sample file re-serialized as a request body (parses+validates shape on the way through). */
	public static String ordersJson(String name) {
		return json(orders(name));
	}

	public static String vehiclesJson(String name) {
		return json(vehicles(name));
	}

	/** Single-order body built from one entry of a sample file. */
	public static String orderJson(OrderRequest order) {
		return json(new OrdersBatchRequest(List.of(order)));
	}

	public static String vehicleJson(VehicleRequest vehicle) {
		return json(new VehiclesBatchRequest(List.of(vehicle)));
	}

	public static String json(Object value) {
		return MAPPER.writeValueAsString(value);
	}

	private static <T> T read(String classpathLocation, Class<T> type) {
		try (InputStream in = SampleData.class.getClassLoader().getResourceAsStream(classpathLocation)) {
			if (in == null) {
				throw new IllegalArgumentException("No classpath resource: " + classpathLocation);
			}
			return MAPPER.readValue(in, type);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
