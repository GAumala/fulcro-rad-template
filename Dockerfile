# syntax=docker/dockerfile:1

# ---- build: compiles the CLJS release bundle and the uberjar --------------------------------
FROM clojure:temurin-21-tools-deps-bookworm AS build
# shadow-cljs needs Node (for npm deps) alongside the JVM; take it from the official image
COPY --from=node:24-bookworm-slim /usr/local/bin/ /usr/local/bin/
COPY --from=node:24-bookworm-slim /usr/local/lib/node_modules/ /usr/local/lib/node_modules/
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0
WORKDIR /app

# Dependency layers first, so they are cached until deps change
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN corepack pnpm install --frozen-lockfile
COPY deps.edn ./
RUN clojure -P -A:cljs && clojure -P -T:build

COPY . .
RUN corepack pnpm exec shadow-cljs release main && clojure -T:build uber

# ---- runtime: just a JRE and the jar --------------------------------------------------------
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app && mkdir /data && chown app /data
USER app
WORKDIR /app
COPY --from=build /app/target/app.jar app.jar
ENV PORT=3000 \
    DATOMIC_STORAGE_DIR=/data
# Datomic Local writes here: mount a persistent volume or you lose data on redeploy
VOLUME /data
EXPOSE 3000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
