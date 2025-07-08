FROM eclipse-temurin:21-jdk-jammy AS build
LABEL authors="dudebehinddude"

WORKDIR /app

COPY . .

RUN chmod +x gradlew

RUN ./gradlew clean shadowJar --no-daemon

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY --from=build /app/build/libs/Globglabbot-*-all.jar app.jar
COPY *.env .

ENTRYPOINT ["java", "-jar", "app.jar"]