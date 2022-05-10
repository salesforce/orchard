# Orchard

> An intentional plantation of trees or shrubs that is maintained for food production.

[![CircleCI](https://circleci.com/gh/salesforce/orchard.svg?style=svg)](https://circleci.com/gh/salesforce/orchard)

Orchard is an orchestration service that manages data pipelines, compute workflows, associated ETL/ELT activities, AND manages the underlying resource lifecycle (provisioning, monitoring, termination).
Inspired by AWS' [Data Pipeline](https://aws.amazon.com/datapipeline/) service, Orchard is designed for enterprise use-cases that demand security, extreme concurrency, granular control over the resource lifecycle, and flexible integration with a cloud-based microservice architecture.

## Design
Like Apache Spark, Orchard is written in functional Scala. This gives orchard the power of Scala's well-developed concurrency features, and in particular, the Actor pattern as enabled by Scala's [Akka](https://github.com/akka/akka) library.

## Setup
Orchard is designed to be deployed into a cloud environment as a service, but can alternatively be set up locally for exploration and development. To do so, follow these steps. 

**Install Apps (OSX)**
```sh
# clone orchard into a local directory
git clone git@github.com:salesforce/orchard.git

# use brew to install Scala Build Tool (SBT)
brew install sbt

# use brew to install postman (for API calls) and docker (if needed)
brew install -cask postman
brew install -cask docker
```

**Configure Postgres Database**

Orchard uses a Postgres database in the docker-compose stack to store the state of each active task. Set the password for this database edit the stack.yml file in the Orchard root directory (shown here as `orchardsecret`):
```yaml
version: '3.1'

services:

  db:
    image: postgres
    restart: always
    ports:
      - 5432:5432
    environment:
      POSTGRES_PASSWORD: orchardsecret

  adminer:
    image: adminer
    restart: always
    ports:
      - 8087:8080
```

Then, export the server address and chosen password with:
```sh
export POSTGRES_SERVER= localhost
export POSTGRES_PASSWORD= orchardsecret
```

**Start the Docker Compose stack**
```sh
docker-compose -f stack.yml up
```

After the stack starts, verify the Postgres database is running by navigating in a browser to `http://localhost:8087`.

**Provision database tables**

Assuming that both SBT and a compatible version of Scala are installed (2.13.8 recommended), the next step is to provision the database tables Orchard will use. From the Orchard root directory:
```sh
sbt "; project orchardCore ; runMain com.salesforce.mce.orchard.tool.ProvisionDatabase"
```

**Start the web service**

As above:
```sh
sbt orchardWS/run
```

**Authentication**

Orchard is by default running a development configuration where authentication is disabled. To enable API authentication, set `orchard.auth.enabled = true` in [application.conf](https://github.com/salesforce/orchard/blob/master/orchard-ws/conf/application.conf). Orchard will then pull the keys specified in 
```
hashed-keys = {
        user = [ ${?MCE_ENV_X_API_USER}, ${?MCE_ENV_X_API_ADMIN_USER} ]
        admin = [ ${?MCE_ENV_X_API_ADMIN}, ${?MCE_ENV_X_API_ADMIN_USER} ]
    }
```
which must match the key provided in the header of any inbound API requests. 

## Using Orchard
Once the setup is complete, Orchard is ready to recieve a number of different instructions via API request.

If deployed into a cloud environment like AWS, Orchard will need a role with an appropriate set of permissions appropriate for the activites. 

Orchard allows the definition and execution of **workflows**, where each workflow consists of a number of **activities**. Activities can be dependant on other activities, forming a directed acyclic graph (DAG). Orchard will execute activities concurrently whenever possible.

Below is an example workflow that defines a number of activites:

```json
{
    "name": "workflowTestName",
    "activities": [
        {
            "id": "activityId_1",
            "name": "first_activity",
            "activityType": "mock.activity.StubActivity",
            "activitySpec": {
                "steps": [
                    {
                        "jar": "command-runner.jar",
                        "args": [
                            "spark-submit",
                            "s3://realstraw-misc-private/managed/health_violations.py",
                            "--data_source",
                            "s3://realstraw-misc-private/data/food_establishment_data.csv",
                            "--output_uri",
                            "s3://realstraw-misc-private/data/output"
                        ]
                    }
                ]
            },
            "resourceId": "resourceId_1",
            "maxAttempt": 2
        },
        {
            "id": "activityId_2",
            "name": "second_activity",
            "activityType": "mock.activity.StubActivity",
            "activitySpec": {
                "steps": [
                    {
                        "jar": "command-runner.jar",
                        "args": [
                            "step started",
                            "modeling in progress",
                            "canceled"
                        ]
                    }
                ]
            },
            "resourceId": "resourceId_2",
            "maxAttempt": 2
        }
    ],
    "resources": [
        {
            "id": "resourceId_1",
            "name": "emr cluster",
            "resourceType": "mock.resource.StubResource",
            "resourceSpec": {
                "releaseLabel": "emr-6.3.0",
                "applications": [
                    "Spark"
                ],
                "serviceRole": "EMR_Role",
                "resourceRole": "mce-sto-service-55p3gg121oe2zy",
                "instancesConfig": {
                    "subnetId": "subnet-6648de2b",
                    "ec2KeyName": "orchard",
                    "instanceCount": 2,
                    "masterInstanceType": "m5.xlarge",
                    "slaveInstanceType": "m5.xlarge"
                }
            },
            "maxAttempt": 2
        },
        {
            "id": "resourceId_2",
            "name": "emr cluster",
            "resourceType": "mock.resource.StubResource",
            "resourceSpec": {
                "releaseLabel": "emr-6.3.0",
                "applications": [
                    "Spark"
                ],
                "serviceRole": "EMR_Role",
                "resourceRole": "mce-sto-service-55p3gg121oe2zy",
                "instancesConfig": {
                    "subnetId": "subnet-6648de2b",
                    "ec2KeyName": "orchard",
                    "instanceCount": 2,
                    "masterInstanceType": "m5.xlarge",
                    "slaveInstanceType": "m5.xlarge"
                }
            },
            "maxAttempt": 2
        }
    ],
    "dependencies": {
        "activityId_2": [
            "activityId_1"
        ]
    }
}
```

To submit this request to Orchard:
```html
POST http://localhost:9000/v1/workflow
```
Which returns a workflow_id. For example: `wf-f231a08f-60e4-480a-b845-e53e06918f77`

Once defined, activate a workflow using the workflow id like so:
```html
PUT http://localhost:9000/v1/workflow/wf-f231a08f-60e4-480a-b845-e53e06918f77
```

**Resource and Activity Types**

In the above example workflow, the activities and resources used are **stubs**. In an actual deployment, Orchard will be using resources and activities specific to the chosen cloud provider's environment, like AWS' EC2 or EMI. Each activity has its own `activitySpec`, which contains configuration needed to carry out that activity.

Currently, Orchard supports:
- AWS EC2 activities / resources
- AWS EMR acvitivites / resources
- AWS S3 resources
- AWS SSM resources
- Shell script activity
- Shell command activity

The project is actively seeking contributions for other activity and resource types, including those relevant to GCP and Azure cloud. A guide to adding new resources and activities will be linked here at a later date for those interested in contributing. 

## Contributing
To contribute to the project, please check issues, fork, and submit a pull request. 

## License
Orchard is an open-source project licensed under BSD 3-Clause "New" or "Revised" License. 

Go [here](https://github.com/salesforce/orchard/blob/master/LICENSE.txt) to read the full text of Orchard's license. 
