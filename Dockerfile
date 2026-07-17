FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
COPY docs/migrations ./docs/migrations

ENV PORT=8080
ENV PFI_AI_SERVICE_URL=http://host.docker.internal:8000
ENV PFI_AI_TIMEOUT_SECONDS=180
ENV PFI_PERSISTENCE_MODE=memory

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
