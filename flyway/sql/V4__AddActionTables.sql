create table "actions" (
    "workflow_id" VARCHAR(64) NOT NULL,
    "action_id" VARCHAR(64) NOT NULL,
    "name" VARCHAR(256) NOT NULL,
    "action_type" VARCHAR(64) NOT NULL,
    "action_spec" TEXT NOT NULL
);

alter table "actions"
add constraint "pk_actions"
primary key("workflow_id","action_id");

alter table "actions"
add constraint "fk_actions_workflows" foreign key("workflow_id") references "workflows"("id")
on update RESTRICT
on delete CASCADE;

create table "activity_actions" (
    "workflow_id" VARCHAR(64) NOT NULL,
    "activity_id" VARCHAR(64) NOT NULL,
    "condition" VARCHAR(32) NOT NULL,
    "action_id" VARCHAR(64) NOT NULL,
    "status" VARCHAR(32) NOT NULL,
    "error_message" TEXT NOT NULL
);

alter table "activity_actions"
add constraint "pk_activity_actions"
primary key("workflow_id","activity_id","condition","action_id");

alter table "activity_actions"
add constraint "fk_activity_actions_actions"
foreign key("workflow_id","action_id") references "actions"("workflow_id","action_id")
on update RESTRICT
on delete CASCADE;

alter table "activity_actions"
add constraint "fk_activity_actions_activities"
foreign key("workflow_id","activity_id") references "activities"("workflow_id","activity_id")
on update RESTRICT
on delete CASCADE;
