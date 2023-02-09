# Orchard

> An intentional plantation of trees or shrubs that is maintained for food production.

[![CircleCI](https://circleci.com/gh/salesforce/orchard.svg?style=svg)](https://circleci.com/gh/salesforce/orchard)

Orchard is an orchestration service that manages data pipelines, compute workflows, associated ETL/ELT activities, AND manages the underlying resource lifecycle (provisioning, monitoring, termination).
Inspired by AWS' [Data Pipeline](https://aws.amazon.com/datapipeline/) service, Orchard is designed for enterprise use-cases that demand security, extreme concurrency, granular control over the resource lifecycle, and flexible integration with a cloud-based microservice architecture.

## Design
Like Apache Spark, Orchard is written in functional Scala. This gives Orchard the power of Scala's well-developed concurrency features, and in particular, the Actor pattern as enabled by Scala's [Akka](https://github.com/akka/akka) library.

## Setup
Orchard is designed to be deployed into a cloud environment as a service, but can alternatively be set up locally for exploration and development. To do so, follow these steps. 

**Install Apps (OSX)**
```sh
# clone orchard into a local directory
git clone git@github.com:salesforce/orchard.git

# use sdkman to install Scala Build Tool (SBT) (if needed)
curl -s "https://get.sdkman.io" | bash
sdk install sbt

# use brew to install postman (for API calls) and docker (if needed)
brew install -cask postman
brew install -cask docker
```

**Configure Postgres Database**

Orchard uses a Postgres database in the docker-compose stack to store the state of each active task. Set the password for this database by adding a [.env file](https://docs.docker.com/compose/environment-variables/#the-env-file) to the project's root containing `ORCHARD_PG_SECRET=orchardsecret`, substituting `orchardsecret` for your own secret.

or set directly in the environment with:
```sh
export ORCHARD_PG_SECRET=orchardsecret
```

**Start the Docker Compose stack**
```sh
docker-compose up
```

This will start the database container, provision the required tables, and start the Orchard web-serivce. 

**Authentication**

Orchard is by default running a development configuration where authentication is disabled. To enable API authentication, set `orchard.auth.enabled = true` in [application.conf](https://github.com/salesforce/orchard/blob/master/orchard-ws/conf/application.conf). Orchard will then pull the keys specified in 
```
hashed-keys = {
        user = [ ${?MCE_ENV_X_API_USER1} , ${?MCE_ENV_X_API_USER2} ]
        admin = [ ${?MCE_ENV_X_API_ADMIN1} , ${?MCE_ENV_X_API_ADMIN2} ]
    }
```
which must match the key provided in the header of any inbound API requests. 

## Using Orchard
Once the setup is complete, Orchard is ready to receive a number of different instructions via API request.

If deployed into a cloud environment like AWS, Orchard will need a role with an appropriate set of permissions appropriate for the activities. 

Orchard allows the definition and execution of **workflows**, where each workflow consists of a number of **activities**. Activities can be dependent on other activities, forming a directed acyclic graph (DAG). Orchard will execute activities concurrently whenever possible.

You can generate an example workflow which will execute a number of activities in an AWS VPC environment with the following command:

```
cd example/data
mustache sample_workflow_view.json sample_workflow.json.mustache > sample_workflow.json
```

We used [mustache](https://github.com/janl/mustache.js/) to substitute values defined in [./examples/data/sample_workflow_view.json] into the final payload.

You can install `mustache` with the following command:
```
npm install -g mustache
```


To submit this request to Orchard:
```html
POST http://localhost:9001/v1/workflow
```
Which returns a workflow_id. For example: `wf-f231a08f-60e4-480a-b845-e53e06918f77`

Once defined, activate a workflow using the workflow id like so:
```html
PUT http://localhost:9001/v1/workflow/wf-f231a08f-60e4-480a-b845-e53e06918f77
```

**Resource and Activity Types**

In the above example workflow, the activities and resources used are **stubs**. In an actual deployment, Orchard will be using resources and activities specific to the chosen cloud provider's environment, like AWS' EC2 or EMI. Each activity has its own `activitySpec`, which contains configuration needed to carry out that activity.

Currently, Orchard supports:
- AWS EC2 activities / resources
- AWS EMR activities / resources
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
