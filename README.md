# Hyperfoil Horreum

A service for storing performance data and regression analysis. Currently, under early development.

Project website: [https://horreum.hyperfoil.io](https://horreum.hyperfoil.io).

## Prerequisites

We have prepared a Docker-compose script to set up PosgreSQL database, Keycloak and create some example users. Therefore you can simply run

```bash
docker-compose -p horreum -f infra/docker-compose.yml up -d
```

and after a few moments everything should be up and ready. You can later configure Keycloak on [localhost:8180](http://localhost:8180) using credentials `admin`/`secret`.
The `horreum` realm already has some roles (`dev-team`) and single user with credentials `user`/`secret` you can use once you start up Horreum.

Note that this docker-compose script is intended for developer use; for production, check out [Horreum operator](https://github.com/Hyperfoil/horreum-operator).

If you are having problems with Grafana login after restarting the infrastructure run `rm horreum-backend/.env .grafana` to wipe out old environment files.

If you are using Podman (and podman-compose) rather than Docker, please use

```bash
infra/podman-compose.sh
```

Due to rootless Podman networking we need to run the containers using `host` networking; There's a bug in recent podman-compose (1.0.3) that won't let you use it through simple `podman-compose up`.

> Note that with podman-compose > 1.x you might also need to install `podman-plugins`.
> :warning: **If postgres fails to start**: remove the volume using `podman volume rm Horreum_horreum_pg12`

Due to subtleties in Podman's rootless network configuration it's not possible to use `docker-compose.yaml`
and we have to use host networking - otherwise Grafana wouldn't be able to connect to Horreum.

If you want to preload the database with some example data you can run

```bash
PGPASSWORD=secret psql -h localhost -U dbadmin horreum -f example-data.sql
```

## Getting Started

> :warning Fedora 36 package distributions of node and openssl have incompatible api versions. You will need to enable the deprecated v1.x openssl provider version for the build to succeed. To do this edit the configuration file `/etc/ssl/openssl.cnf` to uncomment this section
> ``` openssl.conf
> [provider_sect]
> default = default_sect
> legacy = legacy_sect
>
> [default_sect]
> activate = 1
>
> [legacy_sect]
> activate = 1
> ```
 
```bash
cd webapp && npm install && cd ..
./mvnw package
./mvnw quarkus:dev
```

The `package` phase needs to run first in order to build OpenAPI and generate API client for the frontend.

`localhost:3000` to access the create-react-app live code server and `localhost:8080` to access the quarkus development server.

Alternatively you can build Horreum image (with `dev` tag) and run it (assuming that you've started the docker-compose/podman-compose infrastructure):

```bash
# The base image contains tools like curl and jq and horreum.sh script
podman build -f src/main/docker/Dockerfile.jvm.base -t quay.io/hyperfoil/horreum-base:latest .
podman push quay.io/hyperfoil/horreum-base:latest
./mvnw package
podman run --rm --name horreum_app --env-file horreum-backend/.env --network=host quay.io/hyperfoil/horreum:dev
```

> :warning: _If npm install fails_: please try clearing the node module cache `npm cache clean`

## Build tooling set-up

```bash
mvn -N io.takari:maven:wrapper
```

This set's up your environment with the maven wrapper tool

## Security

Security uses RBAC with authz and authn provided by Keycloak server, and heavily relies on row-level security (RLS) in the database.
There should be two DB users (roles); `dbadmin` who has full access to the database, and `appuser` with limited access.
`dbadmin` should set up DB structure - tables with RLS policies and grant RW access to all tables but `dbsecret` to `appuser`.
When the application performs a database query, impersonating the authenticated user, it invokes `SET horreum.userroles = '...'`
to declare all roles the user has based on information from Keycloak. RLS policies makes sure that the user cannot read or modify
anything that does not belong to this user or is made available to him.

As a precaution against bug leaving SQL-level access open the `horreum.userroles` granting the permission are not set in plaintext;
the format of the setting is `role1:seed1:hash1,role2:seed2:hash2,...` where the `hash` is SHA-256 of combination of role, seed
and hidden passphrase. This passphrase is set in `application.properties` under key `horreum.db.secret`, and in database as the only
record in table `dbsecret`. The user `appuser` does not have access to that table, but the security-defined functions used
in table policies can fetch it, compute the hash again and validate its correctness.

We define 3 levels of access to each row:

- public: available even to non-authenticated users (for reading)
- protected: available to all authenticated users that have `viewer` role (see below)
- private: available only to users who 'own' this data.

In addition to these 3 levels, each row defines a random 'token': everyone who knows this token can read the record.
This token should be reset any time the restriction level changes. On database level the token is set using `SET horreum.token = ...`
and the table policies allow read access when the token matches.

It is assumed that the repo will host data for multiple teams; each user is a member of one or more teams.
There are few generic roles that mostly help the UI and serve as an early line of defense by annotating API methods:

- viewer: general permission to view non-public runs
- uploader: permission to upload new runs, useful for bot accounts (CI)
- tester: common user that can define tests, modify or delete data.
- admin: permission both see and change application-wide configuration such as global actions

Each team should have a role with `-team` suffix that will be the owner of the tests/runs, e.g. `engineers-team`.
Uploaders for team's data have `engineers-uploader` which is a composite role, including `engineers-team` and `uploader`.
Bot accounts that only upload data do not need read access that is represented by the `viewer` role; if the account
needs to update a run it needs this role, though (this role is not sufficient to delete anything, though; that requires the `tester` role).
Similar to that testers should get a `engineers-tester` role which is a composite role, including `engineers-team`, `tester` and `viewer`.

## Running in dev mode over HTTPS

> TODO: Dev mode currently does not work over HTTPS (this is Quinoa issue)

By default, the local setup uses plain HTTP. If you need to test HTTPS, run the docker-compose/podman-compose as usual (in this setup the other containers won't be secured) and then run:

```bash
./enable-https.sh
```

This script will amend `.env` file with few extra variables and configure Keycloak to redirect to secured ports. Then you can run

```bash
HTTPS=true mvn quarkus:dev
```

as usual - the `HTTPS=true` will use secured connections on the live-reload proxy on port 3000.

When you want to revert back to plain HTTP, run `./disable-https.sh` and drop the `HTTPS=true` env var.
