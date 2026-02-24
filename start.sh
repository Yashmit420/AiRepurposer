#!/bin/bash

echo "Starting build..."

chmod +x mvnw

./mvnw clean package -DskipTests

echo "Build complete. Starting app..."

java -jar target/*.jar
