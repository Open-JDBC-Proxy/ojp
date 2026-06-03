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

COPY --from=jre-build /custom-jre $JAVA_HOME

WORKDIR /opt/ojp

COPY ojp-libs/ /opt/ojp/ojp-libs/
COPY target/ojp-server-*-shaded.jar /opt/ojp/ojp-server.jar

EXPOSE 1059

ENTRYPOINT ["java", \
    "-Dojp.libs.path=/opt/ojp/ojp-libs", \
    "-Duser.timezone=UTC", \
    "-jar", "/opt/ojp/ojp-server.jar"]
