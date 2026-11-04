-- Spring Modulith event publication registry (spring-modulith-starter-jpa).
-- Records events between modules so a failed listener can be retried; Hibernate validates these tables at startup.
-- Platform tables: no tenant_id and no RLS (events carry their own tenant in the payload).

create table event_publication (
    id                     uuid primary key,
    listener_id            text not null,
    event_type             text not null,
    serialized_event       text not null,
    publication_date       timestamptz not null,
    completion_date        timestamptz,
    status                 text,
    completion_attempts    integer not null default 0,
    last_resubmission_date timestamptz
);
create index event_publication_incomplete on event_publication (publication_date) where completion_date is null;

create table event_publication_archive (
    id                     uuid primary key,
    listener_id            text not null,
    event_type             text not null,
    serialized_event       text not null,
    publication_date       timestamptz not null,
    completion_date        timestamptz,
    status                 text,
    completion_attempts    integer not null default 0,
    last_resubmission_date timestamptz
);
