FROM eclipse-temurin:21-jre

WORKDIR '/app'

COPY build/libs/e2e-test.jar /app/e2e-test.jar

ENTRYPOINT ["java", "-jar", "e2e-test.jar"]