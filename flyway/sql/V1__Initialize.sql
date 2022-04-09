create table if not exists "workflows" (
    "id" VARCHAR(64) NOT NULL PRIMARY KEY,
    "name" VARCHAR(256) NOT NULL,
    "status" VARCHAR(32) NOT NULL,
    "created_at" TIMESTAMP NOT NULL,
    "activated_at" TIMESTAMP,
    "terminated_at" TIMESTAMP
);

create table if not exists "resources" (
    "workflow_id" VARCHAR(64) NOT NULL,
    "resource_id" VARCHAR(64) NOT NULL,
    "name" VARCHAR(256) NOT NULL,
    "resource_type" VARCHAR(64) NOT NULL,
    "resource_spec" TEXT NOT NULL,
    "max_attempt" INTEGER NOT NULL,
    "status" VARCHAR(32) NOT NULL,
    "created_at" TIMESTAMP NOT NULL,
    "activated_at" TIMESTAMP,
    "terminated_at" TIMESTAMP
);

alter table "resources" add constraint "pk_resources" primary key("workflow_id","resource_id");

create table if not exists "resource_instances" (
    "workflow_id" VARCHAR(64) NOT NULL,
    "resource_id" VARCHAR(64) NOT NULL,
    "instance_attempt" INTEGER NOT NULL,
    "instance_spec" VARCHAR,
    "status" VARCHAR(32) NOT NULL,
    "error_message" TEXT NOT NULL,
    "created_at" TIMESTAMP NOT NULL,
    "activated_at" TIMESTAMP,
    "terminated_at" TIMESTAMP
);

alter table "resource_instances" add constraint "pk_resource_instances" primary key(
    "workflow_id", "resource_id", "instance_attempt"
);

create table if not exists "activities" (
    "workflow_id" VARCHAR(64) NOT NULL,
    "activity_id" VARCHAR(64) NOT NULL,
    "name" VARCHAR(256) NOT NULL,
    "activity_type" VARCHAR(64) NOT NULL,
    "activity_spec" TEXT NOT NULL,
    "resource_id" VARCHAR(256) NOT NULL,
    "max_attempt" INTEGER NOT NULL,
    "status" VARCHAR(32) NOT NULL,
    "created_at" TIMESTAMP NOT NULL,
    "activated_at" TIMESTAMP,
    "terminated_at" TIMESTAMP
);

alter table "activities" add constraint "pk_activities" primary key("workflow_id","activity_id");

create table if not exists "dependencies" (
    "workflow_id" VARCHAR(64) NOT NULL,
    "activity_id" VARCHAR(64) NOT NULL,
    "dependent_id" VARCHAR(64) NOT NULL
);

alter table "dependencies" add constraint "pk_depends_on" primary key(
    "workflow_id",
    "activity_id",
    "dependent_id"
);

create table if not exists "activity_attempts" (
    "workflow_id" VARCHAR(64) NOT NULL,
    "activity_id" VARCHAR(64) NOT NULL,
    "attempt" INTEGER NOT NULL,
    "status" VARCHAR(32) NOT NULL,
    "error_message" TEXT NOT NULL,
    "resource_id" VARCHAR(64),
    "resource_instance_attempt" INTEGER,
    "attempt_spec" TEXT,
    "created_at" TIMESTAMP NOT NULL,
    "activated_at" TIMESTAMP,
    "terminated_at" TIMESTAMP
);

alter table "activity_attempts" add constraint "pk_attempts" primary key(
    "workflow_id",
    "activity_id",
    "attempt"
);
