-- Working hours per site, until shifts (rosters) arrive: when people are expected, when they may leave, and how many
-- minutes after the start still count as on time. Used by Today and timesheets for "late" and "left early".

alter table site add column start_time time not null default '09:00';
alter table site add column end_time time not null default '18:00';
alter table site add column late_grace_min smallint not null default 5;
alter table site add constraint site_hours_order check (end_time > start_time);
alter table site add constraint site_grace_range check (late_grace_min between 0 and 120);
