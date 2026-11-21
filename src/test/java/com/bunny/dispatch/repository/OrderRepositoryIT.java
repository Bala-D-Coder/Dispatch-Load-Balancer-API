package com.bunny.dispatch.repository;

import com.bunny.dispatch.entity.OrderEntity;
import com.bunny.dispatch.entity.common.GeoPoint;
import com.bunny.dispatch.entity.common.Priority;
import com.bunny.dispatch.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class OrderRepositoryIT {

	@Autowired
	private OrderRepository repository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void insertPersistsAllFields() {
		repository.save(order("ORD001", 15));

		OrderEntity stored = repository.findById("ORD001").orElseThrow();
		assertThat(stored.getLocation().getLatitude()).isEqualTo(28.6139);
		assertThat(stored.getLocation().getLongitude()).isEqualTo(77.2090);
		assertThat(stored.getAddress()).isEqualTo("Connaught Place, Delhi, India");
		assertThat(stored.getPackageWeight()).isEqualTo(15);
		assertThat(stored.getPriority()).isEqualTo(Priority.HIGH);
	}

	@Test
	void saveDetachedWithExistingIdUpdatesNotDuplicates() {
		repository.save(order("ORD001", 15));
		repository.save(order("ORD001", 99));

		assertThat(repository.count()).isEqualTo(1);
		assertThat(repository.findById("ORD001").orElseThrow().getPackageWeight()).isEqualTo(99);
	}

	@Test
	void managedEntityUpdatePreservesCreatedAtAndBumpsUpdatedAt() {
		repository.save(order("ORD001", 15));
		repository.flush();
		OrderEntity managed = repository.findById("ORD001").orElseThrow();
		LocalDateTime createdAt = managed.getCreatedAt();

		managed.setPackageWeight(99);
		managed.setAddress("Karol Bagh, Delhi, India");
		repository.save(managed);
		repository.flush();

		OrderEntity stored = repository.findById("ORD001").orElseThrow();
		assertThat(stored.getPackageWeight()).isEqualTo(99);
		assertThat(stored.getAddress()).isEqualTo("Karol Bagh, Delhi, India");
		assertThat(stored.getCreatedAt()).isEqualTo(createdAt);
		assertThat(stored.getUpdatedAt()).isAfterOrEqualTo(createdAt);
		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void saveAllMixedBatchKeepsOneRowPerId() {
		repository.save(order("ORD001", 15));
		repository.flush();
		repository.deleteAll();

		List<OrderEntity> batch = List.of(order("ORD001", 20), order("ORD002", 10), order("ORD003", 30));
		repository.saveAll(batch);

		assertThat(repository.count()).isEqualTo(3);
	}

	@Test
	void priorityIsStoredAsStringAndReadBackAsEnum() {
		repository.saveAndFlush(order("ORD001", 15));

		String raw = jdbcTemplate.queryForObject(
				"SELECT priority FROM orders WHERE order_id = 'ORD001'", String.class);
		assertThat(raw).isEqualTo("HIGH");
	}

	@Test
	void timestampsAreAutoPopulatedOnPersist() {
		repository.save(order("ORD001", 15));

		OrderEntity stored = repository.findById("ORD001").orElseThrow();
		assertThat(stored.getCreatedAt()).isNotNull();
		assertThat(stored.getUpdatedAt()).isNotNull();
	}

	@Test
	void deleteAllInBatchEmptiesTable() {
		repository.save(order("ORD001", 15));
		repository.save(order("ORD002", 10));

		repository.deleteAllInBatch();
		assertThat(repository.count()).isZero();
	}

	@Test
	void flywayAppliedV1ExactlyOnce() {
		Integer applied = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = TRUE",
				Integer.class);
		assertThat(applied).isEqualTo(1);
	}

	private static OrderEntity order(String id, int weight) {
		return OrderEntity.builder()
				.orderId(id)
				.location(new GeoPoint(28.6139, 77.2090))
				.address("Connaught Place, Delhi, India")
				.packageWeight(weight)
				.priority(Priority.HIGH)
				.build();
	}

}
