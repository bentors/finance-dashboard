# Etapa 1: Build (Compila o JAR)
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# Etapa 2: Runtime (Executa o JAR de forma leve)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Configurações de memória para o plano gratuito do Render (512MB)
ENTRYPOINT ["java", "-Xmx350m", "-Xss512k", "-jar", "app.jar"]