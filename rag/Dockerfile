# =============================================================================
# Образ бота ФПМИ БГУ: сборка Gradle в одном этапе, лёгкий JRE в другом.
# Модели ONNX не входят в образ (≈ 3 ГБ): они монтируются томом ./models (см. docker-compose.yml и scripts/prepare-models.sh).
# =============================================================================

# --- Этап 1: сборка ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

# Сначала только описание сборки: слой с зависимостями кешируется, пока build.gradle.kts не менялся.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon --console=plain dependencies > /dev/null

COPY src src
# Тесты запускаются отдельно (./gradlew build), в образе нужен только исполняемый архив.
RUN ./gradlew --no-daemon --console=plain bootJar -x test

# --- Этап 2: запуск ---
FROM eclipse-temurin:25-jre
WORKDIR /app

# Отдельный пользователь без прав: процесс бота не должен работать от root.
RUN useradd --system --uid 10001 --home-dir /app bot && mkdir -p /app/models /app/.djl && chown -R bot /app
USER bot

COPY --from=build --chown=bot /src/build/libs/*-SNAPSHOT.jar /app/bot.jar

# Нативная библиотека токенизатора HuggingFace (DJL) скачивается при первом запуске; кеш выносится в том, чтобы не качать заново.
ENV DJL_CACHE_DIR=/app/.djl
# Куча небольшая: модели ONNX живут в нативной памяти, а не в куче JVM.
ENV JAVA_TOOL_OPTIONS="-Xmx2g -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/bot.jar"]
