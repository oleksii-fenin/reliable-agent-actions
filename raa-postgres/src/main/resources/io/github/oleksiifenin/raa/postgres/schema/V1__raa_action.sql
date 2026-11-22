-- reliable-agent-actions journal, schema version 1 (docs/DESIGN.md §7). PostgreSQL >= 14.
-- Plain SQL: run it with Flyway, Liquibase or psql. All times are written with clock_timestamp().

create table raa_action (
    key_hash            bytea        primary key,     -- SHA-256 of the canonical ActionKey (raw key not stored by default)
    key_text            text,                         -- opt-in, for operators; may contain personal data
    tool_name           varchar(200) not null,
    policy              varchar(32)  not null,
    state               varchar(16)  not null check (state in ('EXECUTING','SUCCEEDED','FAILED','UNKNOWN','ABANDONED','ESCALATED')),
    attempt             integer      not null check (attempt >= 1),  -- fencing token
    lease_owner         varchar(64),
    lease_until         timestamptz,
    max_effect_duration interval     not null,
    created_at          timestamptz  not null default clock_timestamp(),
    deadline_at         timestamptz  not null,
    started_at          timestamptz  not null,        -- start of the current attempt
    updated_at          timestamptz  not null default clock_timestamp(),
    resolve_after       timestamptz,
    lookup_failures     integer      not null default 0,
    failure_basis       varchar(8)   check (failure_basis in ('DEFINITE','LOOKUP')),
    all_args_hash       bytea        not null,        -- incl. free-text arguments
    scope_hash          bytea        not null,
    material_args_hash  bytea        not null,
    arguments_json      jsonb,                        -- opt-in only (personal data)
    result_text         text,                         -- needed for replay; personal data -> retention, redact()
    last_error          text,
    redacted_at         timestamptz,                  -- set by redact(): texts erased, none stored again
    check (state <> 'FAILED' or failure_basis is not null)
);

create table raa_action_call (                        -- every provider call id ever mapped to an action
    scope_hash          bytea        not null,
    provider_call_id    varchar(200) not null,
    key_hash            bytea        not null references raa_action (key_hash) on delete cascade,
    attempt             integer      not null,        -- latest attempt started by this call
    seen_at             timestamptz  not null default clock_timestamp(),
    primary key (scope_hash, provider_call_id)
);

create index raa_action_call_key      on raa_action_call (key_hash);
create index raa_action_expired_lease on raa_action (lease_until)   where state = 'EXECUTING';
create index raa_action_resolve       on raa_action (resolve_after nulls first) where state in ('UNKNOWN','FAILED');
create index raa_action_terminal      on raa_action (updated_at)    where state in ('SUCCEEDED','ABANDONED');
create index raa_action_unresolved    on raa_action (updated_at)    where state in ('UNKNOWN','ESCALATED');
