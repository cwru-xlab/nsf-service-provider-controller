# Base image
FROM gradle:7.1.0-jdk16 AS builder

# Set the working directory
WORKDIR /app

# Copy the source code to the container
COPY . .

# Base image
FROM openjdk:16-oracle

# Set the working directory
WORKDIR /app

# Copy the shadow JAR from the builder stage to the final image
COPY --from=builder /app/app/build/libs/app-all.jar ./app.jar

# Start the application
CMD ["java", "-jar", "app.jar"]