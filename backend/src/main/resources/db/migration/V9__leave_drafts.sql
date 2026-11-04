-- Leave planner: requests can be saved as drafts (planned, not sent for approval, not counted in balances).
alter table leave_request drop constraint leave_request_status_check;
alter table leave_request add constraint leave_request_status_check
    check (status in ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED', 'RETURNED', 'CANCELLED'));
create index leave_request_drafts on leave_request (tenant_id, employee_id) where status = 'DRAFT';
