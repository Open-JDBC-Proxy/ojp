# Stage 1: Build custom JRE with jlink
FROM eclipse-temurin:24-jdk-alpine AS jre-build

RUN jlink \
    --add-modules java.base,java.compiler,java.desktop,java.management,java.naming,java.rmi,java.scripting,java.security.jgss,java.sql,jdk.httpserver,jdk.unsupported \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=zip-6 \
    --output /custom-jre

# Stage 2: Runtime image
FROM alpine:3.21

ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Default ports for the gRPC server and Prometheus metrics endpoint.
# These ENV declarations serve two purposes:
#   1. They are the default values the server reads at startup.
#   2. They make the defaults visible in `docker inspect` and tooling.
#
# Override at runtime by passing environment variables — the server reads
# OJP_SERVER_PORT and OJP_PROMETHEUS_PORT directly:
#
#   docker run -e OJP_SERVER_PORT=8080 -e OJP_PROMETHEUS_PORT=9091 \
#              -p 8080:8080 -p 9091:9091 rrobetti/ojp:<version>
#
# NOTE: EXPOSE is evaluated at build time using the ENV defaults below, so
# the image metadata will always list 1059 and 9159 regardless of what is
# passed at runtime. When overriding ports, the -p mapping must match the
# -e value — Docker does not update EXPOSE dynamically.
ENV OJP_SERVER_PORT=1059
ENV OJP_PROMETHEUS_PORT=9159

COPY --from=jre-build /custom-jre $JAVA_HOME

WORKDIR /opt/ojp

COPY ojp-libs/ /opt/ojp/ojp-libs/
COPY target/ojp-server-*-shaded.jar /opt/ojp/ojp-server.jar

EXPOSE ${OJP_SERVER_PORT} ${OJP_PROMETHEUS_PORT}

ENTRYPOINT ["java", \
    "-Dojp.libs.path=/opt/ojp/ojp-libs", \
    "-Duser.timezone=UTC", \
    "-jar", "/opt/ojp/ojp-server.jar"]
