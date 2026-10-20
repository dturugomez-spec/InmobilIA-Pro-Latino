# ═══════════════════════════════════════════════════════════════════
#  InmobilIA Pro · Dockerfile simple y probado + JVM optimizada 512MB
# ═══════════════════════════════════════════════════════════════════

# ─── Stage 1: build con Maven + JDK 21 ───
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

ENV MAVEN_OPTS="-Xmx512m -XX:MaxMetaspaceSize=192m"

COPY mvnw mvnw.cmd pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline

COPY src ./src
RUN ./mvnw -q -B -DskipTests clean package

# ─── Stage 2: runtime ligero JRE 21 ───
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

COPY --from=builder /workspace/target/*.jar app.jar

ENV PORT=8080
EXPOSE 8080

# Health check (start-period amplio para arranque lazy)
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=10 \
    CMD wget -qO- http://localhost:${PORT}/healthz || exit 1

# ─── JVM optimizada para plan con RAM holgada (Render Standard 2GB+) ───
#   Heap 70% del contenedor (en 2GB ≈ 1.4GB de heap → muchísimo margen)
#   G1GC: el mejor recolector para apps web con buen throughput y baja pausa
#   String dedup + compresión de punteros para aprovechar mejor la RAM
#   ExitOnOutOfMemoryError → si algún día peta, muere limpio y se ve en log
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+UseStringDeduplication -XX:+UseCompressedOops -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=${PORT} -jar /app/app.jar"]
