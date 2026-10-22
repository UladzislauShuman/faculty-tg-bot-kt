import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.21"
    // Открывает классы с аннотациями Spring для прокси (Kotlin-классы по умолчанию final).
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    // Форматирование. Замечания ломают сборку, исправляются командой ktlintFormat.
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "shuman.bsu.famcs"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// Версии библиотек, которые не управляются BOM Spring Boot. Собраны в одном месте, чтобы их было легко найти.
val langchain4jVersion = "1.15.0"
val qdrantClientVersion = "1.17.0" // линейка клиента совпадает с линейкой сервера (qdrant/qdrant:v1.17.x)
val ktgbotapiVersion = "37.0.0"
val jsoupVersion = "1.23.2"
val flexmarkVersion = "0.64.8"
val onnxRuntimeVersion = "1.22.0"
val djlTokenizersVersion = "0.36.0"
val luceneVersion = "10.5.2"
val resilience4jVersion = "2.4.0"
val kotlinLoggingVersion = "8.0.4"
val testcontainersVersion = "2.0.5"
val mockkVersion = "1.14.11"
val springMockkVersion = "5.0.1"
val archunitVersion = "1.5.1"

// Spring Boot фиксирует kotlinx-coroutines 1.10.2, а ktgbotapi 37 и Ktor 3.5 собраны под 1.11.0: с 1.10.2 любой запрос
// к Telegram падает с NoSuchMethodError (Job.invokeOnCompletion). Версия поднята через свойство управления зависимостями Spring.
extra["kotlin-coroutines.version"] = "1.11.0"

dependencyManagement {
    imports {
        mavenBom("dev.langchain4j:langchain4j-bom:$langchain4jVersion")
        mavenBom("org.testcontainers:testcontainers-bom:$testcontainersVersion")
    }
}

dependencies {
    // --- Spring Boot 4: модульные стартеры ---
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // --- Kotlin ---
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava")
    implementation("io.github.oshai:kotlin-logging-jvm:$kotlinLoggingVersion")

    // --- LangChain4j: только клиенты моделей и ONNX-модели. Порядок шагов пишем сами (явный оркестратор) ---
    implementation("dev.langchain4j:langchain4j")
    implementation("dev.langchain4j:langchain4j-ollama")
    implementation("dev.langchain4j:langchain4j-open-ai")

    // --- Модели на CPU: свой тонкий слой над ONNX Runtime и токенизатором HuggingFace (см. knowledge/embedding) ---
    implementation("com.microsoft.onnxruntime:onnxruntime:$onnxRuntimeVersion")
    implementation("ai.djl.huggingface:tokenizers:$djlTokenizersVersion")

    // --- Знания: Qdrant, парсинг HTML, текстовый анализ ---
    implementation("io.qdrant:client:$qdrantClientVersion")
    implementation("org.jsoup:jsoup:$jsoupVersion")
    implementation("com.vladsch.flexmark:flexmark-html2md-converter:$flexmarkVersion")
    implementation("org.apache.lucene:lucene-analysis-common:$luceneVersion")

    // --- Устойчивость вызовов моделей ---
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:$resilience4jVersion")
    implementation("io.github.resilience4j:resilience4j-kotlin:$resilience4jVersion")

    // --- Telegram ---
    implementation("dev.inmo:tgbotapi:$ktgbotapiVersion")

    // --- Тесты ---
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("io.mockk:mockk:$mockkVersion")
    testImplementation("com.ninja-squad:springmockk:$springMockkVersion")
    // Проверка границ между пакетами (см. ArchitectureTest).
    testImplementation("com.tngtech.archunit:archunit-junit5:$archunitVersion")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-qdrant")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        // Строгая проверка nullability аннотаций Spring (JSpecify, JSR-305) и перенос аннотаций на параметр и свойство.
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
        // Предупреждения компилятора — ошибки: накопленные предупреждения никто не читает.
        allWarningsAsErrors = true
    }
}

ktlint {
    version.set("1.8.0")
    android.set(false)
    ignoreFailures.set(false)
}

tasks.test {
    useJUnitPlatform {
        // Тяжёлые тесты запускаются отдельными командами; обычная сборка остаётся быстрой:
        //   integration — нужен Docker (PostgreSQL, Qdrant), live — реальные провайдеры, model — файлы моделей из models/,
        //   eval — проверка качества на наборе вопросов (нужны запущенный Qdrant с индексом и файлы моделей).
        excludeTags("integration", "live", "model", "eval")
    }
}

// Интеграционные тесты: поднимают PostgreSQL и Qdrant в контейнерах (нужен запущенный Docker).
tasks.register<Test>("integrationTest") {
    description = "Интеграционные тесты с Testcontainers (PostgreSQL, Qdrant)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.test)
}

// Тесты паритета моделей: сверяют эмбеддинги и реранкер с Python (нужны файлы из ./scripts/prepare-models.sh).
tasks.register<Test>("modelTest") {
    description = "Паритет моделей ONNX с Python (нужны файлы моделей)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("model")
    }
    // Две модели в памяти: эмбеддинги (2.2 ГБ весов) и реранкер (0.7 ГБ).
    maxHeapSize = "6g"
    shouldRunAfter(tasks.test)
}

// Проверка качества на наборе вопросов `eval/qa-set.yaml`: поиск по настоящему индексу. Отчёты в build/reports/eval/.
tasks.register<Test>("evalTest") {
    description = "Качество поиска на наборе вопросов (нужны Qdrant с индексом и файлы моделей)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("eval")
    }
    maxHeapSize = "6g"
    // Результат зависит от внешнего состояния (Qdrant, Ollama), поэтому задача всегда выполняется заново, а не берётся из кеша.
    outputs.upToDateWhen { false }
    // Строки прогресса из тестов («N из M, осталось …») видны в консоли во время долгого прогона.
    testLogging {
        showStandardStreams = true
    }
    // Параметры проверок передаются в тест из командной строки: -Peval.labels=provisional, -Peval.qaSet=путь.
    listOf("eval.labels", "eval.qaSet", "eval.botUrl", "eval.profile", "eval.positives", "eval.answers", "eval.judgeDir").forEach { key ->
        project.findProperty(key)?.let { systemProperty(key, it) }
    }
    // Печать сводки в консоль сборки.
    testLogging {
        showStandardStreams = true
    }
    shouldRunAfter(tasks.test)
}
