package com.bunny.dispatch.entity.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PriorityTest {

	@Test
	void rankOrderIsHighThenMediumThenLow() {
		assertThat(Priority.HIGH.rank()).isLessThan(Priority.MEDIUM.rank());
		assertThat(Priority.MEDIUM.rank()).isLessThan(Priority.LOW.rank());
	}

	@Test
	void ranksAreZeroBasedOrdinals() {
		assertThat(Priority.HIGH.rank()).isZero();
		assertThat(Priority.MEDIUM.rank()).isEqualTo(1);
		assertThat(Priority.LOW.rank()).isEqualTo(2);
	}

	@Test
	void enumHasExactlyTheSpecValues() {
		assertThat(Priority.values()).containsExactly(Priority.HIGH, Priority.MEDIUM, Priority.LOW);
	}

}
