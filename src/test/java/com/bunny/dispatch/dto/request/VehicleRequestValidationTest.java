package com.bunny.dispatch.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleRequestValidationTest {

	private static ValidatorFactory factory;
	private static Validator validator;

	@BeforeAll
	static void setUp() {
		factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@AfterAll
	static void tearDown() {
		factory.close();
	}

	@Test
	void validBaselineHasNoViolations() {
		assertThat(validator.validate(valid())).isEmpty();
	}

	@Test
	void capacityBounds() {
		assertViolationOn(withCapacity(0), "capacity");
		assertViolationOn(withCapacity(-10), "capacity");
		assertThat(validator.validate(withCapacity(1))).isEmpty();
		assertViolationOn(withCapacity(100001), "capacity");
	}

	@Test
	void currentCoordinateBounds() {
		assertThat(validator.validate(withLat(90.0))).isEmpty();
		assertViolationOn(withLat(90.5), "currentLatitude");
		assertThat(validator.validate(withLon(-180.0))).isEmpty();
		assertViolationOn(withLon(-180.5), "currentLongitude");
	}

	@Test
	void blankOrMissingFieldsRejected() {
		assertViolationOn(withVehicleId(""), "vehicleId");
		assertViolationOn(withVehicleId(null), "vehicleId");
		assertViolationOn(withAddress("  "), "currentAddress");
		assertViolationOn(withCapacity(null), "capacity");
		assertViolationOn(withLat(null), "currentLatitude");
		assertViolationOn(withLon(null), "currentLongitude");
	}

	// currentAddress is VARCHAR(512), vehicleId VARCHAR(64); see OrderRequestValidationTest for why
	// the width has to be asserted here rather than left to the schema.
	@Test
	void lengthLimitsMatchTheColumnWidths() {
		assertThat(validator.validate(withAddress("a".repeat(512)))).isEmpty();
		assertViolationOn(withAddress("a".repeat(513)), "currentAddress");
		assertThat(validator.validate(withVehicleId("V".repeat(64)))).isEmpty();
		assertViolationOn(withVehicleId("V".repeat(65)), "vehicleId");
	}

	@Test
	void everyViolationCarriesHumanReadableMessage() {
		Set<ConstraintViolation<VehicleRequest>> violations =
				validator.validate(new VehicleRequest(null, null, null, null, null));
		assertThat(violations).hasSize(5);
		violations.forEach(v -> assertThat(v.getMessage()).isNotBlank());
	}

	private static void assertViolationOn(VehicleRequest request, String property) {
		assertThat(validator.validate(request))
				.anyMatch(v -> v.getPropertyPath().toString().equals(property));
	}

	private static VehicleRequest valid() {
		return new VehicleRequest("VEH001", 100, 12.9716, 77.6413, "Indiranagar, Bangalore, Karnataka, India");
	}

	private static VehicleRequest withCapacity(Integer capacity) {
		VehicleRequest b = valid();
		return new VehicleRequest(b.vehicleId(), capacity, b.currentLatitude(), b.currentLongitude(),
				b.currentAddress());
	}

	private static VehicleRequest withLat(Double lat) {
		VehicleRequest b = valid();
		return new VehicleRequest(b.vehicleId(), b.capacity(), lat, b.currentLongitude(), b.currentAddress());
	}

	private static VehicleRequest withLon(Double lon) {
		VehicleRequest b = valid();
		return new VehicleRequest(b.vehicleId(), b.capacity(), b.currentLatitude(), lon, b.currentAddress());
	}

	private static VehicleRequest withVehicleId(String id) {
		VehicleRequest b = valid();
		return new VehicleRequest(id, b.capacity(), b.currentLatitude(), b.currentLongitude(), b.currentAddress());
	}

	private static VehicleRequest withAddress(String address) {
		VehicleRequest b = valid();
		return new VehicleRequest(b.vehicleId(), b.capacity(), b.currentLatitude(), b.currentLongitude(), address);
	}

}
