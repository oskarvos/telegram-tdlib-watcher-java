# ===== 1) Сборка JAR (Gradle, без Alpine) =====
FROM gradle:8.7-jdk21 AS app-build
WORKDIR /home/gradle/src
# Оптимизация кэша
COPY build.gradle settings.gradle gradle.properties* ./
COPY gradle ./gradle
RUN gradle --version
# Исходники
COPY . .
RUN gradle bootJar --no-daemon

# ===== 2) Сборка TDLib (Ubuntu, glibc) =====
FROM ubuntu:24.04 AS tdlib-build
ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    git cmake g++ make \
    zlib1g-dev libssl-dev libsqlite3-dev libzstd-dev \
    gperf \
    ca-certificates \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
RUN git clone --depth 1 https://github.com/tdlib/td.git
WORKDIR /src/td
RUN mkdir build && cd build && cmake -DCMAKE_BUILD_TYPE=Release .. \
 && cmake --build . --target tdjson -j"$(nproc)"

# ===== 3) Runtime: JRE + зависимости TDLib =====
FROM eclipse-temurin:21-jre-jammy
ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    libssl3 zlib1g libsqlite3-0 libzstd1 ca-certificates \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=app-build   /home/gradle/src/build/libs/*.jar /app/app.jar
COPY --from=tdlib-build /src/td/build/libtdjson.so        /opt/tdlib/libtdjson.so

ENV LD_LIBRARY_PATH=/opt/tdlib
ENV JAVA_TOOL_OPTIONS="-Dtd.lib-path=/opt/tdlib/libtdjson.so -Djna.nosys=false"
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
