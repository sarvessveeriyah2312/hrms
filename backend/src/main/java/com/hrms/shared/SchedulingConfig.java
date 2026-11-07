package com.hrms.shared;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on scheduled jobs (e.g. overdue approval reminders). Set {@code hrms.scheduling.enabled=false} to switch them off. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "hrms.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {
}
