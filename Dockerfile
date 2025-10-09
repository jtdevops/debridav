FROM gradle:8.11-jdk21 AS build

WORKDIR /app

# Copy the Gradle wrapper and build files
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY gradle.properties .

# Copy the source code
COPY src src

# Build the application
RUN ./gradlew bootJar

# Runtime stage
FROM openjdk:21-jdk-slim

WORKDIR /app

RUN apt update && apt install curl -y

RUN mkdir app
# Copy the built JAR from the build stage
COPY --from=build /app/build/libs/debridav-*.jar app/app.jar

EXPOSE 8080

CMD ["java", "-jar", "app/app.jar"]
