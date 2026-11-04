package com.hrms.workflow;

import java.util.UUID;

/**
 * Implemented by each module whose requests need approval (leave now; claims and attendance later).
 * The workflow engine calls it in the same transaction as the approval, with the tenant already bound.
 */
public interface ApprovalSubject {

    /** e.g. "LEAVE". Stored on the workflow instance. */
    String subjectType();

    /** Permission module: approvers need {@code <module>:approve}, e.g. "leave". */
    String module();

    /** How the request reads in an approver's inbox. */
    Summary summarize(UUID subjectId);

    /**
     * Called once when the request is approved, rejected, returned for changes or cancelled.
     * {@code comment} is the deciding approver's comment (required for REJECTED and RETURNED).
     */
    void onFinished(UUID subjectId, Outcome outcome, String comment);

    /** Where the requester sees this request in the web app (used in notifications). */
    default String requesterLink(UUID subjectId) {
        return "/app";
    }

    record Summary(String title, String detail, String tone) {
    }

    enum Outcome { APPROVED, REJECTED, RETURNED, CANCELLED }
}
