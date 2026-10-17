package shuman.bsu.famcs.rag

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Точка входа бота ФПМИ БГУ.
 *
 * Один процесс содержит все модули (модульный монолит): адаптер Telegram, ядро, индексатор.
 * Классы свойств находит сканирование, плановые задачи (ночная индексация, очистка) включает [EnableScheduling].
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class RagApplication

/** Запускает приложение. Профиль (`yandex`, `docker`) выбирается переменной окружения `SPRING_PROFILES_ACTIVE`. */
fun main(args: Array<String>) {
    runApplication<RagApplication>(*args)
}
