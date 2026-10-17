-- Суточные счётчики пользователя переведены с Telegram ID на хеш (HMAC с секретом) и отвязаны от app_user.
-- Причина: счётчик с внешним ключом и каскадным удалением обнулялся командой /forget, и один человек обходил суточный лимит
-- (забыть себя, согласиться заново, спросить ещё 20 раз). Хеш необратим без секрета, поэтому после /forget счётчик остаётся
-- обезличенным и удаляется по сроку хранения (bot.retention.usage-days).
-- Старые счётчики не переносятся: это суточные числа, их потеря безвредна.
DROP TABLE usage_counter;

CREATE TABLE usage_counter (
    user_hash TEXT   NOT NULL,
    day       DATE   NOT NULL,
    messages  INT    NOT NULL DEFAULT 0,
    tokens    BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_hash, day)
);
