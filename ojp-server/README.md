## Docker Image

### Build docker image locally

> mvn compile jib:dockerBuild

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
  rrobetti/ojp:0.4.7-beta
```

For comprehensive Docker deployment examples and configuration options, see the **[Docker Deployment Guide](../documents/configuration/DOCKER_DEPLOYMENT.md)**.

