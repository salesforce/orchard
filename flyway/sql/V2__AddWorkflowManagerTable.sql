create table "workflow_managers" (
    "workflow_id" VARCHAR(64) NOT NULL PRIMARY KEY,
    "manager_id" VARCHAR(64) NOT NULL,
    "last_checkin" TIMESTAMP NOT NULL
);

alter table "workflow_managers" add constraint "fk_workflow_managers_workflows"
foreign key("workflow_id") references "workflows"("id")
on update RESTRICT
on delete CASCADE;

-- add missing foreign keys from V1
alter table "resources" add constraint "fk_resources_workflows"
foreign key("workflow_id") references "workflows"("id")
on update RESTRICT
on delete CASCADE;

alter table "resource_instances" add constraint "fk_resource_instances_resources"
foreign key("workflow_id","resource_id") references "resources"("workflow_id","resource_id")
on update RESTRICT
on delete CASCADE;

alter table "activities" add constraint "fk_activities_resources"
foreign key("workflow_id","resource_id") references "resources"("workflow_id","resource_id")
on update RESTRICT
on delete CASCADE;

alter table "activities" add constraint "fk_activities_workflows"
foreign key("workflow_id") references "workflows"("id")
on update RESTRICT
on delete CASCADE;

alter table "dependencies" add constraint "fk_dependency_activity_dependee"
foreign key("workflow_id","dependent_id") references "activities"("workflow_id","activity_id")
on update RESTRICT
on delete CASCADE;

alter table "dependencies" add constraint "fk_dependency_activity_dependent"
foreign key("workflow_id","activity_id") references "activities"("workflow_id","activity_id")
on update RESTRICT
on delete CASCADE;

alter table "activity_attempts" add constraint "fk_attempts_activities"
foreign key("workflow_id","activity_id") references "activities"("workflow_id","activity_id")
on update RESTRICT
on delete CASCADE;

alter table "activity_attempts" add constraint "fk_attempts_resource_instances"
foreign key("workflow_id","resource_id","resource_instance_attempt") references "resource_instances"("workflow_id","resource_id","instance_attempt")
on update RESTRICT
on delete CASCADE;
