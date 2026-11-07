package com.hrms.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** When an overdue step gets another reminder. */
class ApprovalRemindersTests {

    private static final Instant NOW = Instant.parse("2026-11-07T09:00:00Z");

    private static ApprovalReminders.Due due(Instant remindedAt, int count) {
        return new ApprovalReminders.Due(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(), null, null, "MANAGER",
            NOW.minus(Duration.ofHours(5)), remindedAt, count, null, "LEAVE", UUID.randomUUID(), "leave", UUID.randomUUID(), NOW);
    }

    @Test
    void remindsOnceWhenOverdueThenDaily() {
        assertThat(ApprovalReminders.shouldRemind(due(null, 0), NOW)).isTrue();
        assertThat(ApprovalReminders.shouldRemind(due(NOW.minus(Duration.ofHours(3)), 1), NOW)).isFalse();
        assertThat(ApprovalReminders.shouldRemind(due(NOW.minus(Duration.ofHours(25)), 1), NOW)).isTrue();
    }

    @Test
    void stopsAfterThreeReminders() {
        assertThat(ApprovalReminders.shouldRemind(due(NOW.minus(Duration.ofDays(2)), ApprovalReminders.MAX_REMINDERS), NOW)).isFalse();
    }
}
