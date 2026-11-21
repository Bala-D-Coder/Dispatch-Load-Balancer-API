package com.bunny.dispatch.entity;

import com.bunny.dispatch.entity.common.GeoPoint;
import com.bunny.dispatch.entity.common.Priority;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderEntity {

	@Id
	@Column(name = "order_id", length = 64)
	private String orderId;

	@Embedded
	@AttributeOverride(name = "latitude", column = @Column(name = "latitude"))
	@AttributeOverride(name = "longitude", column = @Column(name = "longitude"))
	private GeoPoint location;

	@Column(name = "address", length = 512)
	private String address;

	@Column(name = "package_weight")
	private Integer packageWeight;

	@Enumerated(EnumType.STRING)
	@Column(name = "priority", length = 10)
	private Priority priority;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	@PrePersist
	void onCreate() {
		createdAt = LocalDateTime.now();
		updatedAt = createdAt;
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = LocalDateTime.now();
	}

}
