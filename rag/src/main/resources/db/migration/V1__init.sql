-- Схема PostgreSQL бота ФПМИ. Сроки хранения и правила приватности: docs/final_report.md, разделы 6.4 и 12.2.
-- Имя, username и телефон пользователей не хранятся вообще.

-- Пользователи. Telegram ID нужен, чтобы отвечать и считать лимиты. Согласие записывается вместе с версией текста.
CREATE TABLE app_user (
    telegram_id     BIGINT      PRIMARY KEY,
    consent_version TEXT,
    consent_at      TIMESTAMPTZ,
    last_seen_at    TIMESTAMPTZ NOT NULL,
    -- false, если пользователь заблокировал бота: ответы ему больше не отправляются.
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL
);

-- Сессия — непрерывный диалог. Закрывается после паузы или командой /new. Сводка хранится в сессии.
CREATE TABLE chat_session (
    id                          BIGSERIAL   PRIMARY KEY,
    telegram_id                 BIGINT      NOT NULL REFERENCES app_user (telegram_id) ON DELETE CASCADE,
    summary                     TEXT,
    -- Сообщения до этого номера уже учтены в сводке: сводка дополняется, а не пересоздаётся.
    summarized_until_message_id BIGINT      NOT NULL DEFAULT 0,
    started_at                  TIMESTAMPTZ NOT NULL,
    last_activity_at            TIMESTAMPTZ NOT NULL,
    closed_at                   TIMESTAMPTZ
);
-- Не более одной активной сессии на пользователя.
CREATE UNIQUE INDEX ux_chat_session_active ON chat_session (telegram_id) WHERE closed_at IS NULL;

CREATE TABLE message (
    id         BIGSERIAL   PRIMARY KEY,
    session_id BIGINT      NOT NULL REFERENCES chat_session (id) ON DELETE CASCADE,
    author     TEXT        NOT NULL CHECK (author IN ('USER', 'ASSISTANT')),
    body       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_message_session ON message (session_id, id);
CREATE INDEX ix_message_created ON message (created_at);

-- Журнал ответов для оценки качества. Telegram ID здесь нет: только хеш с солью. При /forget хеш и запрос стираются.
CREATE TABLE answer_log (
    id              BIGSERIAL   PRIMARY KEY,
    message_id      BIGINT      REFERENCES message (id) ON DELETE SET NULL,
    user_hash       TEXT,
    rewritten_query TEXT,
    route           TEXT        NOT NULL,
    status          TEXT        NOT NULL,
    no_info         BOOLEAN     NOT NULL,
    -- Адреса источников через пробел.
    doc_ids         TEXT,
    provider        TEXT,
    model           TEXT,
    tokens_in       INT         NOT NULL,
    tokens_out      INT         NOT NULL,
    latency_ms      INT         NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_answer_log_user ON answer_log (user_hash);
CREATE INDEX ix_answer_log_created ON answer_log (created_at);

-- Оценки ответов 1–5. Удаляются вместе с пользователем.
CREATE TABLE feedback (
    id          BIGSERIAL   PRIMARY KEY,
    answer_id   BIGINT      NOT NULL REFERENCES answer_log (id) ON DELETE CASCADE,
    telegram_id BIGINT      NOT NULL REFERENCES app_user (telegram_id) ON DELETE CASCADE,
    rating      INT         NOT NULL CHECK (rating BETWEEN 1 AND 5),
    reason      TEXT,
    comment     TEXT,
    created_at  TIMESTAMPTZ NOT NULL,
    -- Повторное нажатие кнопки перезаписывает оценку, а не создаёт вторую.
    UNIQUE (answer_id, telegram_id)
);

-- Суточные счётчики пользователя и системы: лимиты вопросов и расход токенов.
CREATE TABLE usage_counter (
    telegram_id BIGINT NOT NULL REFERENCES app_user (telegram_id) ON DELETE CASCADE,
    day         DATE   NOT NULL,
    messages    INT    NOT NULL DEFAULT 0,
    tokens      BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (telegram_id, day)
);

CREATE TABLE system_usage (
    day      DATE   PRIMARY KEY,
    messages INT    NOT NULL DEFAULT 0,
    tokens   BIGINT NOT NULL DEFAULT 0
);

-- Номера уже обработанных обновлений Telegram: повторная доставка не обрабатывается и не тратит токены.
CREATE TABLE processed_update (
    update_id   BIGINT      PRIMARY KEY,
    received_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_processed_update_received ON processed_update (received_at);

-- Запуски индексации. Номер запуска служит версией данных в Qdrant.
CREATE TABLE index_run (
    id            BIGSERIAL   PRIMARY KEY,
    mode          TEXT        NOT NULL CHECK (mode IN ('INCREMENTAL', 'FULL')),
    started_at    TIMESTAMPTZ NOT NULL,
    finished_at   TIMESTAMPTZ,
    status        TEXT        NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'ABORTED', 'FAILED')),
    pages_found   INT         NOT NULL DEFAULT 0,
    pages_changed INT         NOT NULL DEFAULT 0,
    pages_failed  INT         NOT NULL DEFAULT 0,
    pages_removed INT         NOT NULL DEFAULT 0,
    message       TEXT
);

-- Страницы-источники: хеш содержимого позволяет пересчитывать эмбеддинги только у изменившихся.
CREATE TABLE source_document (
    url          TEXT        PRIMARY KEY,
    content_hash TEXT        NOT NULL,
    title        TEXT,
    checked_at   TIMESTAMPTZ NOT NULL,
    version      BIGINT      NOT NULL,
    chunk_count  INT         NOT NULL
);

-- Кто из администраторов какую команду вызывал.
CREATE TABLE admin_audit (
    id       BIGSERIAL   PRIMARY KEY,
    admin_id BIGINT      NOT NULL,
    command  TEXT        NOT NULL,
    at       TIMESTAMPTZ NOT NULL
);
