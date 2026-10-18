# Бот ФПМИ БГУ (Kotlin)

RAG-бот для Telegram: отвечает студентам и абитуриентам по материалам сайта fpmi.bsu.by. Порт Python-версии на Kotlin, Spring Boot 4, LangChain4j, PostgreSQL, Qdrant.

## Как это работает

```
Сообщение → предпроверки → лимиты → очередь → контекст диалога → переформулирование → классификатор → ветка → ответ с источниками
```

| Ветка | Что делает |
|---|---|
| RAG | Гибридный поиск в Qdrant (смысл + слова), реранкер, ответ модели строго по найденным разделам |
| SCHEDULE | Ссылка на расписание на сайте |
| SMALLTALK | Приветствия и вопросы к самому боту |
| GENERAL | Общие вопросы без фактов о факультете |

Каждый ответ с документами содержит источники и дату их проверки. Детали устройства — `docs/` в репозитории `faculty-tg-bot`, итоговые решения — `final_report.md`.

## Структура

Пакеты лежат в `src/main/kotlin/shuman/bsu/famcs/rag/`. Модуль → функция → слой.

| Модуль | Ответственность |
|---|---|
| `ui/telegram`, `ui/http` | Интерфейсы пользователя: Telegram (команды, кнопки, длинный опрос) и HTTP для проверок. Знают только `api` и `platform` (правило проверяет `ArchitectureTest`) |
| `api` | Граница системы: интерфейсы (`RagService`, `UserService`, `FeedbackService`, `ConversationService`, `AdminService`) и данные запроса и ответа |
| `assistant` | Путь вопроса: `pipeline` (предпроверки, оркестратор, вход `RagServiceImpl`), `intent` (классификатор и ветки), `dialog` (память и переформулирование), `user`, `limit` (лимиты, бюджет, очередь), `feedback` (журнал ответов и оценки) |
| `knowledge` | База знаний: `chunking`, `embedding`, `rerank`, `sparse` (BM25), `store` (Qdrant), `source` (обход сайта), `indexing`, `retrieval`, `health` |
| `llm` | Языковые модели: провайдеры, цепочка с повтором и резервом, размыкатель, проверка ответов |
| `admin` | Команды администратора, статистика, очистка данных по срокам |
| `platform` | Общее: идентификаторы, база данных, пулы потоков, промпты, хеширование пользователей, оповещения |

Внутри функции (`limit`, `feedback`, `knowledge/indexing` и т.д.) слои называются одинаково:

| Папка | Что в ней |
|---|---|
| `service` | Интерфейс сервиса; реализации лежат в `service/implementation`, имя заканчивается на `Impl` |
| `repository` | Интерфейс хранилища; SQL только в `repository/jdbc`, имя заканчивается на `RepositoryJdbcImpl` |
| `model` | Данные без логики |
| `exception` | Исключения функции |

В каждом модуле есть `configuration` (классы сборки бинов, один класс на функцию) и `configuration/properties` (все `*Properties`, привязанные к `application.yaml`).

Любую заменяемую часть (классификатор, переформулирование, хранилище знаний, реранкер, провайдер модели, обработчик ветки) можно подменить реализацией интерфейса: сборка в `*Configuration`.

### С чего читать код

| Что смотреть | Где |
|---|---|
| Приём сообщения Telegram | `ui/telegram/handler/TelegramBotHandler` (`onMessage`, `onCallback`) |
| Приём HTTP-вопроса | `ui/http/controller/HttpEntryController` |
| Вход в систему | `api/RagService` → `assistant/pipeline/service/implementation/RagServiceImpl` |
| Порядок шагов одного вопроса | `assistant/pipeline/service/AnswerPipeline` |
| Ветка с поиском по базе знаний | `assistant/intent/handler/implementation/RagHandler` → `knowledge/retrieval/KnowledgeRetriever` |
| Генерация ответа по найденным разделам (отдельно от поиска) | `assistant/intent/generator/implementation/LlmAnswerGenerator` |
| Трасса ответа (ветка, запрос поиска, найденные разделы, модель, токены) | `api/model/AnswerTrace`; в HTTP-входе `POST /api/v1/ask?trace=true` |
| Обновление базы знаний | `knowledge/indexing/Indexer` (запуск: `knowledge/indexing/job`) |
| Кто кого получает | `assistant/configuration/*Configuration`, `knowledge/configuration`, `llm/configuration` |

## Запуск

Нужны: JDK 25, Docker, Ollama с моделями `llama3.1` и `qwen2.5:14b-instruct-q4_K_M` (или ключ YandexGPT).

```bash
# 1. Модели ONNX (эмбеддинги bge-m3 берутся из кеша HuggingFace, реранкер экспортируется из Python-проекта)
./scripts/prepare-models.sh

# 2. PostgreSQL и Qdrant
cp .env.example .env        # заполнить POSTGRES_PASSWORD и BOT_HASH_SECRET
docker compose up -d postgres qdrant

# 3. Бот (HTTP-вход для проверок вместо Telegram)
POSTGRES_PASSWORD=... BOT_HASH_SECRET=... BOT_HTTP_ENABLED=true BOT_HTTP_API_KEY=... ./gradlew bootRun
```

При первом запуске пустая база знаний индексируется автоматически (обход сайта и расчёт эмбеддингов на процессоре занимает минуты).

Проверка вопроса без Telegram:

```bash
curl -s -X POST localhost:8080/api/v1/ask -H 'Content-Type: application/json' -H "X-Api-Key: $BOT_HTTP_API_KEY" \
  -d '{"userId": 1, "text": "Когда проходит практика?"}'
```

Весь стек в Docker: `MODELS_COPY=1 ./scripts/prepare-models.sh && docker compose up -d --build`.

### Профили и секреты

| Профиль | Что включает |
|---|---|
| (по умолчанию) | Локальный Ollama, Telegram и HTTP-вход выключены |
| `yandex` | YandexGPT (`YANDEX_API_KEY`, `YANDEX_FOLDER_ID` обязательны: без них бот не запустится) |
| `docker` | Адреса контейнеров; журнал цветным текстом |
| `json-logs` | Журнал в JSON вместо цветного текста (для разбора машиной) |
| `deepseek`, `qwen` | DeepSeek и Qwen через OpenAI-совместимый API (`DEEPSEEK_API_KEY`; `QWEN_API_KEY` и `QWEN_BASE_URL`) |

Секреты читаются только из окружения (`.env.example`). Telegram включается `BOT_TELEGRAM_ENABLED=true` и `BOT_TELEGRAM_TOKEN`.

## Конфиг

Все параметры в `src/main/resources/application.yaml`, у каждого подпись. Пометка `[паритет]` — значение совпадает с Python-версией, менять только после замера. Значения, влияющие на деньги и безопасность: `bot.limits`, `bot.llm.roles`, `bot.retention`.

## Тесты

| Команда | Что проверяет |
|---|---|
| `./gradlew build` | Форматирование (ktlint), компиляция без предупреждений, юнит-тесты (быстрые, без сети) |
| `./gradlew integrationTest` | PostgreSQL и Qdrant в контейнерах (нужен Docker), сборка всего контекста приложения |
| `./gradlew modelTest` | Паритет моделей ONNX с Python (нужны файлы из `models/`) |
| `./gradlew evalTest` | Качество поиска на наборе вопросов `eval/qa-set.yaml` (нужны Qdrant с индексом и файлы моделей). Отчёт: `build/reports/eval/retrieval.md` |

## Команды бота

`/start` (согласие), `/new`, `/forget`, `/help`. Администраторам (список в `bot.telegram.admin-ids`): `/admin_stats`, `/admin_feedback`, `/admin_gaps`, `/admin_reindex` (обычное обновление) и `/admin_reindex full` (полная пересборка после смены модели эмбеддингов или нарезки), `/admin_maintenance on|off`.

Остановка бота корректная: он перестаёт принимать сообщения и дорабатывает уже принятые вопросы (до 95 с), поэтому `docker compose stop` ждёт до 130 с.
