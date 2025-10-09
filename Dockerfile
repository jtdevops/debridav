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
RUN ./gradlew build -x test

# Runtime stage
FROM openjdk:21-jdk-slim

WORKDIR /app

RUN apt-get update

RUN mkdir app
# Copy the built JAR from the build stage
COPY --from=build /app/build/libs/debridav-0.1.0.jar app/app.jar

EXPOSE 8080

CMD ["java", "-jar", "app/app.jar"]
