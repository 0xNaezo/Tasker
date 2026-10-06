# 0008. Уровни SDK и инструменты сборки

## Статус

Принято, 2026-10-06.

## Контекст

- Основной телефон владельца работает на Android 13. Сборка с minSdk выше 33 на него не встанет. Это ограничение внёс в план коммит «Plan: cap minSdk at 33, add install channels and direct AI mode» ([план, §1 и §5](../tech-plan.md#5-стек-и-минимальные-версии)).
- С 31.08.2026 Google Play требует targetSdk 36 для новых приложений и обновлений (план, §5).
- Уровень 26 даёт java.time и каналы уведомлений без обходных путей; desugaring не нужен (план, §5).

## Решение

Уровни SDK заданы в одном месте — в объекте `Sdk` в [`ProjectExtensions.kt`](../../build-logic/convention/src/main/kotlin/app/tasker/buildlogic/ProjectExtensions.kt). Convention-плагины из `build-logic` применяют их ко всем Android-модулям. После коммита «Plan: cap minSdk…» код использует такие значения:

| Уровень | Значение | Почему |
| --- | --- | --- |
| `minSdk` | 26 (Android 8.0) | не выше 33, чтобы приложение ставилось на телефон владельца; java.time без desugaring |
| `targetSdk` | 36 (Android 16) | требование Google Play; на установку не влияет |
| `compileSdk` | 37 | нужен текущим библиотекам AndroidX (комментарий в `ProjectExtensions.kt`); на установку не влияет |

Версии библиотек и плагинов — в [`gradle/libs.versions.toml`](../../gradle/libs.versions.toml), версия Gradle — в [`gradle-wrapper.properties`](../../gradle/wrapper/gradle-wrapper.properties). Основные:

| Инструмент | Версия |
| --- | --- |
| Gradle | 9.8.0 |
| Android Gradle Plugin | 9.4.1 |
| Kotlin | 2.4.20 |
| KSP | 2.3.12 |
| Compose BOM | 2026.09.00 |
| Navigation 3 | 1.2.0 |
| material3-adaptive | 1.3.0 |
| Room | 2.8.5 |
| Hilt | 2.60.1 |
| WorkManager | 2.12.0 |
| Glance | 1.2.0 |
| Robolectric | 4.17 |
| Roborazzi | 1.76.0 |
| Macrobenchmark и плагин Baseline Profile | 1.5.0 |
| ktlint | 1.8.0, Gradle-плагин 14.2.0 |
| detekt | 1.23.8 |

- **Байт-код — Java 17.** `sourceCompatibility`, `targetCompatibility` и `jvmTarget` равны 17 ([`KotlinAndroid.kt`](../../build-logic/convention/src/main/kotlin/app/tasker/buildlogic/KotlinAndroid.kt)).
- **Convention-плагины:** `tasker.android.application`, `tasker.android.library`, `tasker.android.feature`, `tasker.android.compose`, `tasker.android.hilt`, `tasker.android.room`, `tasker.android.test` и `tasker.jvm.library`.
- **Проверки качества:**
  - ktlint с правилами Android и detekt подключаются в каждом модуле через `configureQuality()` ([`Quality.kt`](../../build-logic/convention/src/main/kotlin/app/tasker/buildlogic/Quality.kt)). detekt берёт конфигурацию из `config/detekt/detekt.yml` и свой набор правил `tasker` из модуля `:lint:detekt-rules`;
  - Android Lint настроен для Android-модулей в `configureKotlinAndroid()`: `abortOnError` и конфигурация `config/lint/lint.xml`.
- **detekt и Kotlin.** detekt 1.23 встроен в Kotlin 2.0.21, поэтому classpath detekt закреплён на этой версии Kotlin.

## Последствия

- Приложение ставится на Android 8.0 и новее, в том числе на Android 13.
- Возможности новых версий Android включаются по уровню API, на старых работает запасной путь. Примеры из кода:
  - скрытие содержимого в списке недавних: `setRecentsScreenshotEnabled` на Android 13+, `FLAG_SECURE` на более старых;
  - плитка открывает быстрый ввод через `PendingIntent` на Android 14+, через `Intent` — раньше;
  - пример виджета в системном выборе виджетов — только на Android 15+;
  - язык приложения выбирают в его настройках. На Android 8–12 выбор хранит `AppLocalesMetadataHolderService`, на Android 13+ язык можно выбрать и в системных настройках (`generateLocaleConfig = true`).
- Для сборки нужен Android SDK Platform 37.
- Зависимость, которой нужен minSdk выше 26, сломает слияние манифестов: `tools:overrideLibrary` в проекте не используется.
- Версия JDK в репозитории не закреплена: нет ни `jvmToolchain`, ни критериев JVM для демона Gradle. Workflows CI ставят Temurin 21 (`.github/workflows/`). Локально нужен JDK 17 или новее.
- Пока проект на detekt 1.23, Kotlin внутри detekt остаётся 2.0.21, какой бы ни была версия Kotlin проекта.

## Альтернативы

- **minSdk выше 33.** Отвергнут: сборка не встанет на телефон владельца.
- **compileSdk 36, равный targetSdk.** Не подходит: текущим библиотекам AndroidX нужен compileSdk 37.
