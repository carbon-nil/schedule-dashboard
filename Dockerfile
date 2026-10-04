# 本番用イメージ。frontend と backend をビルドし、1 プロセスで UI と API を配信する。
FROM node:22-bookworm-slim AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM sbtscala/scala-sbt:eclipse-temurin-21.0.12_8_1.13.0_3.3.8 AS backend
WORKDIR /src/backend
COPY backend/project ./project
COPY backend/build.sbt ./
RUN sbt update
COPY backend/src ./src
RUN sbt assembly

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend /src/backend/target/scala-3.3.8/schedule-dashboard.jar ./
COPY --from=frontend /src/frontend/dist ./public
ENV STATIC_DIR=/app/public \
    DATABASE_PATH=/data/schedule.db
VOLUME /data
EXPOSE 8080
CMD ["java", "-jar", "/app/schedule-dashboard.jar"]
