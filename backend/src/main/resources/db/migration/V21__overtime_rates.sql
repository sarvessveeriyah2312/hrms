-- Overtime rates set by the overtime rule: one for a working day, a rest day and a public holiday (the Employment Act
-- minimums by default). rate_mode AUTO: the rate comes from the kind of day; EMPLOYEE: the employee picks one of the
-- rule's rates on each request (the one for that day is suggested). Rates are 1.0× to 5.0× in quarter steps.

alter table overtime_rule
    add column rate_working numeric(4, 2) not null default 1.5,
    add column rate_rest    numeric(4, 2) not null default 2.0,
    add column rate_holiday numeric(4, 2) not null default 3.0,
    add column rate_mode    text not null default 'AUTO' check (rate_mode in ('AUTO', 'EMPLOYEE'));
alter table overtime_rule add constraint overtime_rule_rates_check check (
    rate_working between 1 and 5 and rate_rest between 1 and 5 and rate_holiday between 1 and 5
    and rate_working * 4 = trunc(rate_working * 4) and rate_rest * 4 = trunc(rate_rest * 4)
    and rate_holiday * 4 = trunc(rate_holiday * 4));

-- Requests keep the rate they were sent with, now any rule rate; and the kind of day it was worked out for.
alter table overtime_request drop constraint overtime_request_rate_check;
alter table overtime_request alter column rate type numeric(4, 2);
alter table overtime_request add constraint overtime_request_rate_check
    check (rate is null or (rate between 1 and 5 and rate * 4 = trunc(rate * 4)));
alter table overtime_request add column day_kind text check (day_kind in ('WORKING', 'REST', 'HOLIDAY'));
