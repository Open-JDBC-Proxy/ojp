## Create a docker image locally

> mvn compile jib:dockerBuild

**Note**: The Docker image does NOT include database JDBC drivers to keep the image size minimal and avoid licensing issues. To use the image with your database, you need to add the required JDBC drivers either by:

1. **Mounting drivers as a volume:**
```bash
docker run --rm -d -v /path/to/drivers:/app/libs/drivers --network host rrobetti/ojp:0.3.2-beta
```

2. **Extending the image with a Dockerfile:**
```dockerfile
FROM rrobetti/ojp:0.3.2-beta
COPY postgresql-42.7.8.jar /app/libs/
COPY mysql-connector-j-9.5.0.jar /app/libs/
```

## Create a docker image locally and push to docker hub
PS: Only authorized users.
> docker login

> mvn compile jib:build

