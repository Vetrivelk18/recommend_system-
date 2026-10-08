# Spring Boot app + the Angular build it serves from src/main/resources/static.
# One image, one process: the frontend is already compiled into the jar's static folder,
# so there is no Node stage here.

# ---- build ----
# The Maven image rather than ./mvnw: the wrapper jar is gitignored, so the script would
# have to download Maven itself on every build, and needs curl and unzip to do it.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# pom.xml alone first, so the dependency layer is reused until the pom changes and a
# source-only edit rebuilds in seconds instead of re-downloading everything.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src/ src/
# Tests are skipped on purpose: contextLoads needs a live database, which a build has not got.
RUN mvn -B -q package -DskipTests

# ---- run ----
FROM eclipse-temurin:17-jre
RUN useradd --uid 1001 --no-create-home app
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER app

# Sized for a 1 GiB Cloud Run instance.
#   MaxRAMPercentage=60   the JVM's default is 25% of the container, which is a ~256 MB
#                         heap here; 60% leaves the rest for metaspace, threads and buffers
#   UseSerialGC           one vCPU, small heap - the parallel collectors only add overhead
#   TieredStopAtLevel=1   faster startup, which is what a cold start is made of
#   ExitOnOutOfMemoryError  die cleanly and let Cloud Run start a fresh instance, rather
#                         than limp on half-alive after an OOM
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"

# Cloud Run injects PORT (8080 by default); server.port reads it.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
