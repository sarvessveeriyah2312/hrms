package com.hrms.workflow;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Tells the workflow whether an approver is away, so their backup can act. The leave module implements it
 * (approved leave covering the day). Without an implementation nobody is considered away.
 */
public interface ApproverAvailability {

    boolean isAway(UUID employeeId, LocalDate day);
}
