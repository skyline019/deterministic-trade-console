FROM node:22-alpine AS frontend-build
WORKDIR /frontend
# BuildKit/daemon proxy is not always inherited; allow explicit args for constrained networks.
ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG ALL_PROXY
ARG NO_PROXY
ENV HTTP_PROXY=${HTTP_PROXY} HTTPS_PROXY=${HTTPS_PROXY} ALL_PROXY=${ALL_PROXY} NO_PROXY=${NO_PROXY} \
    http_proxy=${HTTP_PROXY} https_proxy=${HTTPS_PROXY} all_proxy=${ALL_PROXY} no_proxy=${NO_PROXY}
# Prefer a reachable registry when npmjs times out behind regional networks.
ARG NPM_REGISTRY=https://registry.npmmirror.com
RUN npm config set registry "${NPM_REGISTRY}"
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG ALL_PROXY
ARG NO_PROXY
ENV HTTP_PROXY=${HTTP_PROXY} HTTPS_PROXY=${HTTPS_PROXY} ALL_PROXY=${ALL_PROXY} NO_PROXY=${NO_PROXY} \
    http_proxy=${HTTP_PROXY} https_proxy=${HTTPS_PROXY} all_proxy=${ALL_PROXY} no_proxy=${NO_PROXY}
# ops/maven/settings.xml mirrors Central to a regional endpoint; CI runners use plain Central.
ARG MAVEN_SETTINGS=ops/maven/settings.xml
COPY ${MAVEN_SETTINGS} /root/.m2/settings.xml
COPY pom.xml ./
COPY src ./src
COPY --from=frontend-build /frontend/dist ./src/main/resources/static
# BuildKit cache keeps ~/.m2 across rebuilds; Aliyun mirror for constrained networks.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests -Dexec.skip=true package

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /workspace/target/deterministic-trade-1.0.0-SNAPSHOT.jar /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
