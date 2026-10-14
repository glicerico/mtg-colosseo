# MTG Colosseum server: XMage rules engine + web UI + agent API on port 7070
#   docker build -t mtg-colosseum .
#   docker run -p 7070:7070 -v colosseum-data:/app/data mtg-colosseum
# Seats need their tokens, revealing hands and stopping games need the owner token. Add
# -e COLOSSEUM_API_KEY=... to restrict who can create games, or -e COLOSSEUM_ARGS="--auth open" for a trusted,
# local-only setup.
FROM maven:3.9-eclipse-temurin-21 AS build
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY scripts/build_xmage.sh scripts/
COPY scripts/xmage-patches scripts/xmage-patches
RUN XMAGE_DIR=/src/.xmage scripts/build_xmage.sh && rm -rf /src/.xmage
COPY engine engine
# recorded in every game record: docker build --build-arg SOURCE_COMMIT=$(git rev-parse HEAD) .
ARG SOURCE_COMMIT=unknown
RUN cd engine && mvn -B -q package -Dsource.commit=$SOURCE_COMMIT

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/engine/target/colosseum-engine.jar engine/target/
COPY --from=build /src/engine/target/lib engine/target/lib
COPY web web
COPY decks decks
RUN mkdir -p data
VOLUME /app/data
EXPOSE 7070
# XMage keeps its card database in ./db of the working directory (built on first start, ~1 minute)
WORKDIR /app/data
ENV JAVA_OPTS="-Xmx4g"
ENV COLOSSEUM_ARGS=""
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /app/engine/target/colosseum-engine.jar --host 0.0.0.0 --port 7070 --web /app/web --decks /app/decks --data /app/data $COLOSSEUM_ARGS"]
