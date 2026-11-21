package com.bunny.dispatch.dto.request;

import com.bunny.dispatch.entity.common.Priority;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OrderRequestValidationTest {

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
	void latitudeBoundariesAreInclusive() {
		assertThat(validator.validate(withLat(90.0))).isEmpty();
		assertThat(validator.validate(withLat(-90.0))).isEmpty();
		assertViolationOn(withLat(90.001), "latitude");
		assertViolationOn(withLat(-91.0), "latitude");
	}

	@Test
	void longitudeBoundariesAreInclusive() {
		assertThat(validator.validate(withLon(180.0))).isEmpty();
		assertThat(validator.validate(withLon(-180.0))).isEmpty();
		assertViolationOn(withLon(180.1), "longitude");
		assertViolationOn(withLon(-181.0), "longitude");
	}

	@Test
	void zeroCoordinateIsValidDataNotNull() {
		assertThat(validator.validate(new OrderRequest("ORD001", 0.0, 0.0, "Null Island", 10, Priority.HIGH)))
				.isEmpty();
	}

	@Test
	void packageWeightBounds() {
		assertViolationOn(withWeight(0), "packageWeight");
		assertViolationOn(withWeight(-5), "packageWeight");
		assertThat(validator.validate(withWeight(1))).isEmpty();
		assertViolationOn(withWeight(10001), "packageWeight");
	}

	@Test
	void blankOrMissingOrderIdRejected() {
		assertViolationOn(withOrderId(""), "orderId");
		assertViolationOn(withOrderId("   "), "orderId");
		assertViolationOn(withOrderId(null), "orderId");
	}

	@Test
	void blankAddressRejected() {
		assertViolationOn(withAddress(""), "address");
	}

	// address is VARCHAR(512) and orderId is VARCHAR(64) in V1. `ddl-auto: validate` checks that the
	// columns EXIST, not their width, so an over-long value used to travel all the way to Postgres
	// and return 500 INTERNAL_ERROR. The code layer is the validation authority, so the limit lives here.
	@Test
	void lengthLimitsMatchTheColumnWidths() {
		assertThat(validator.validate(withAddress("a".repeat(512)))).isEmpty();
		assertViolationOn(withAddress("a".repeat(513)), "address");
		assertThat(validator.validate(withOrderId("O".repeat(64)))).isEmpty();
		assertViolationOn(withOrderId("O".repeat(65)), "orderId");
	}

	@Test
	void nullPriorityAndCoordinatesRejected() {
		assertViolationOn(withPriority(null), "priority");
		assertViolationOn(withLat(null), "latitude");
		assertViolationOn(withLon(null), "longitude");
		assertViolationOn(withWeight(null), "packageWeight");
	}

	@Test
	void everyViolationCarriesHumanReadableMessage() {
		Set<ConstraintViolation<OrderRequest>> violations =
				validator.validate(new OrderRequest(null, null, null, null, null, null));
		assertThat(violations).hasSize(6);
		violations.forEach(v -> assertThat(v.getMessage()).isNotBlank());
	}

	private static void assertViolationOn(OrderRequest request, String property) {
		assertThat(validator.validate(request))
				.anyMatch(v -> v.getPropertyPath().toString().equals(property));
	}

	private static OrderRequest valid() {
		return new OrderRequest("ORD001", 12.9716, 77.5946, "MG Road, Bangalore, Karnataka, India", 10,
				Priority.HIGH);
	}

	private static OrderRequest withLat(Double lat) {
		OrderRequest b = valid();
		return new OrderRequest(b.orderId(), lat, b.longitude(), b.address(), b.packageWeight(), b.priority());
	}

	private static OrderRequest withLon(Double lon) {
		OrderRequest b = valid();
		return new OrderRequest(b.orderId(), b.latitude(), lon, b.address(), b.packageWeight(), b.priority());
	}

	private static OrderRequest withWeight(Integer weight) {
		OrderRequest b = valid();
		return new OrderRequest(b.orderId(), b.latitude(), b.longitude(), b.address(), weight, b.priority());
	}

	private static OrderRequest withOrderId(String id) {
		OrderRequest b = valid();
		return new OrderRequest(id, b.latitude(), b.longitude(), b.address(), b.packageWeight(), b.priority());
	}

	private static OrderRequest withAddress(String address) {
		OrderRequest b = valid();
		return new OrderRequest(b.orderId(), b.latitude(), b.longitude(), address, b.packageWeight(), b.priority());
	}

	private static OrderRequest withPriority(Priority priority) {
		OrderRequest b = valid();
		return new OrderRequest(b.orderId(), b.latitude(), b.longitude(), b.address(), b.packageWeight(), priority);
	}

}
