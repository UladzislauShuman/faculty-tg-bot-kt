package shuman.bsu.famcs.rag

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * Проверка границ между модулями. Структура папок читается глазами, а эти правила не дают ей разрушиться со временем.
 *
 * Модули: `ui` (Telegram, HTTP) знает только `api` и `platform`; `api` описывает границу и ничего не знает о реализациях;
 * SQL живёт только в `repository.jdbc`; настройки и сборка бинов лежат только в `configuration`.
 */
@AnalyzeClasses(packages = ["shuman.bsu.famcs.rag"], importOptions = [ImportOption.DoNotIncludeTests::class])
class ArchitectureTest {
    /** Интерфейс пользователя не должен знать, как устроены ответы, знания, модели и администрирование. */
    @ArchTest
    val uiKnowsOnlyApiAndPlatform: ArchRule = noClasses()
        .that()
        .resideInAPackage("..rag.ui..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..rag.assistant..", "..rag.knowledge..", "..rag.llm..", "..rag.admin..")
        .because("UI общается с системой только через пакет api")

    /** Граница системы описывает контракт и не зависит от реализаций. */
    @ArchTest
    val apiDoesNotKnowImplementations: ArchRule = noClasses()
        .that()
        .resideInAPackage("..rag.api..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..rag.ui..", "..rag.assistant..", "..rag.knowledge..", "..rag.llm..", "..rag.admin..")

    /** Общий слой ничего не знает о функциях системы. */
    @ArchTest
    val platformIsIndependent: ArchRule = noClasses()
        .that()
        .resideInAPackage("..rag.platform..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..rag.ui..", "..rag.assistant..", "..rag.knowledge..", "..rag.llm..", "..rag.admin..", "..rag.api..")

    /** Доступ к базе данных только из хранилищ (сборка бинов в `configuration` лишь передаёт его): сервисы работают через `*Repository`. */
    @ArchTest
    val databaseOnlyInRepositories: ArchRule = noClasses()
        .that()
        .resideOutsideOfPackages("..repository.jdbc..", "..rag.platform..", "..configuration..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("shuman.bsu.famcs.rag.platform.database.Database")

    /** Все настройки привязаны к конфигу в одном месте модуля. */
    @ArchTest
    val propertiesInPropertiesPackage: ArchRule = classes()
        .that()
        .areAnnotatedWith("org.springframework.boot.context.properties.ConfigurationProperties")
        .should()
        .resideInAPackage("..configuration.properties..")

    /** Классы сборки бинов лежат в `configuration`, рядом с настройками. */
    @ArchTest
    val configurationsInConfigurationPackage: ArchRule = classes()
        .that()
        .areAnnotatedWith("org.springframework.context.annotation.Configuration")
        .should()
        .resideInAPackage("..configuration..")
}
