## Docker Image

The base image is a custom Alpine JRE built with `jlink` (only the modules required by OJP), bringing the final image size to **68MB**.

### Build the base image (first time or when dependencies change)

```bash
# From the repository root
docker build -f ojp-server/base.Dockerfile -t rrobetti/ojp-base:jre24-alpine .
```

### Build the app image locally

```bash
mvn -pl ojp-server/ jib:dockerBuild -Djib.from.image=docker://rrobetti/ojp-base:jre24-alpine
```

### Run locally

```bash
docker run -p 1059:1059 rrobetti/ojp:0.4.9-SNAPSHOT
```

### Build and push to Docker Hub
PS: Only authorized users.
> docker login

> mvn compile jib:build

### Run Docker image with JVM parameters

You can pass JVM parameters to the Docker container using the `JAVA_TOOL_OPTIONS` environment variable:

```bash
docker run -d \
  -p 1059:1059 \
  -e JAVA_TOOL_OPTIONS="-Xmx4g -Xms2g -Dfile.encoding=UTF-8 -Duser.timezone=UTC" \
  rrobetti/ojp:0.4.16-beta
```

For comprehensive Docker deployment examples and configuration options, see the **[Docker Deployment Guide](../documents/configuration/DOCKER_DEPLOYMENT.md)**.

