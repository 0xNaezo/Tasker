# Tasker

Tasker — Android-менеджер задач без обслуживания: список, который поддерживает себя сам. Задачу вводят одной строкой, и правила сразу находят в ней даты, проект и оценку. План дня собирается из рабочих часов и занятости в календаре. Задачи, которые долго не трогали, сами уходят на проверку актуальности, а каждое действие автоматики показано с причиной и отменяется в течение 30 дней. Данные хранятся на телефоне, аккаунт не нужен. ИИ включается по желанию и только дозаполняет то, чего не нашли правила. Приложение написано на Kotlin и Jetpack Compose.

- [Техническое задание](docs/tz-task-manager.md)
- [Технический план](docs/tech-plan.md)
- [Архитектурные решения (ADR)](docs/adr/README.md)
- [Спайки S1–S8: что проверено, а что нет](docs/spikes/README.md)
- [ИИ-прокси (backend)](backend/README.md)
- [Оценка качества ИИ](docs/ai-eval/README.md)
- [Политика конфиденциальности — черновик](docs/privacy-policy.md) и [ответы для Data safety](docs/play-data-safety.md)

## Модули

Все модули перечислены в `settings.gradle.kts`. Пометка [JVM] — чистый Kotlin без Android SDK.

| Модуль | Назначение |
| --- | --- |
| `:app` | Точка входа: `MainActivity`, навигация, граф Hilt, `Application`, отчёты о сбоях |
| `:core:model` | [JVM] Доменные типы: задача, проект, событие, настройки |
| `:core:domain` | [JVM] Правила, статусы, план и ёмкость, происхождение полей, часы `DayClock` |
| `:core:testing` | Управляемые часы и фабрики данных для тестов |
| `:core:database` | Room: сущности, DAO, FTS4-индекс, экспорт схем |
| `:core:parser` | [JVM] Разбор строки ввода, языковые пакеты ru, uk, en |
| `:core:data` | Команды, журнал событий, отмена, план дня, догоняющая обработка, поиск, настройки, недельные метрики |
| `:core:designsystem` | Тема, цвета, отступы, токен просрочки `overdue` |
| `:core:ui` | Общие компоненты: строка задачи, чипы, шкала ёмкости, лист переноса, сообщения |
| `:core:ai-contract` | [JVM] Маршруты ИИ: промпты, схемы, проверка ответов. Общий для приложения и backend |
| `:core:ai-openrouter` | [JVM] Вызов модели через OpenRouter на клиенте Ktor ([ADR 0011](docs/adr/0011-openrouter-provider.md)) |
| `:core:ai` | `AiGateway`: режимы «выключено», «напрямую» и «через прокси»; согласие, ключ API, очередь дозаполнения; отправка недельной статистики через прокси |
| `:core:backup` | Экспорт, ежедневные копии, правила Auto Backup, восстановление |
| `:core:calendar` | Занятость из календаря устройства |
| `:core:notifications` | Каналы, шлюз и построение уведомлений, действия, ссылки `tasker://` |
| `:core:scheduling` | WorkManager и будильники: догоняющая обработка, утренний план, напоминания, тихие часы |
| `:feature:capture` | Строка ввода, быстрый ввод, «Поделиться», плитка, ярлыки, голос |
| `:feature:today` | «Сегодня» и «План дня» |
| `:feature:inbox` | «Входящие» и быстрый разбор |
| `:feature:tasks` | «Неделя», «Когда-нибудь», «Проекты», карточка проекта |
| `:feature:task` | Карточка задачи, пауза и снимки контекста |
| `:feature:review` | Проверка актуальности |
| `:feature:settings` | Настройки, ИИ, данные, онбординг |
| `:feature:done` | Лог сделанного и сводки |
| `:feature:search` | Поиск, фильтры, архив |
| `:feature:journal` | Журнал автоматики |
| `:widget` | Виджет «Сегодня» на Glance |
| `:baselineprofile` | Генерация Baseline Profile и замер холодного старта (Macrobenchmark) |
| `:lint:detekt-rules` | Свои правила detekt: `ForbiddenClockCall`, `OverdueColorOnly` |
| `:backend` | ИИ-прокси для сборок из Google Play: Ktor и PostgreSQL |
| `:tools:ai-eval` | Прогон оценки качества маршрута enrich |

## Требования

- **JDK 17 или новее.** Байт-код проекта — Java 17, а Gradle 9 работает только на JDK 17+. Репозиторий версию JDK не закрепляет; CI собирает на Temurin 21.
- **Android SDK** с платформой 37 (`compileSdk`). Путь к SDK — `sdk.dir` в `local.properties` или переменная `ANDROID_HOME`. Файл `local.properties` в git не попадает.
- Для тестов на эмуляторах (e2e, Baseline Profile) — аппаратное ускорение эмулятора, на Linux это KVM. Образы эмуляторов Gradle скачивает сам.
- Для локального запуска backend — PostgreSQL 14+ ([backend/README.md](backend/README.md)).

Gradle запускается через `./gradlew` (версия 9.8.0 из wrapper).

## Сборка и проверки

Команды запускают из корня репозитория.

### Сборка

```bash
./gradlew :app:assembleGithubDebug   # канал github, отладочная сборка
./gradlew :app:assemblePlayDebug     # канал play, отладочная сборка
```

APK появляется в `app/build/outputs/apk/<канал>/<тип>/`, например `app/build/outputs/apk/github/debug/app-github-debug.apk`. Поставить сборку на подключённый телефон: `./gradlew :app:installGithubDebug`.

### Проверки кода

```bash
./gradlew ktlintCheck detekt     # форматирование и статический анализ всех модулей
./gradlew :app:lintGithubDebug   # Android Lint приложения
./gradlew lint                   # Android Lint всех модулей, как в CI
```

`./gradlew ktlintFormat` исправляет форматирование. Правила, которые проверяют detekt и Lint, описаны в разделе «Правила кода».

### Unit-тесты

```bash
./gradlew unitTests                     # как в CI: debug-вариант каждого Android-модуля (у app — githubDebug) и JVM-модули
./gradlew test                          # все unit-тесты всех модулей и всех вариантов
./gradlew :core:parser:test             # один JVM-модуль
./gradlew :core:data:testDebugUnitTest  # один Android-модуль
```

Задача `unitTests` объявлена в корневом `build.gradle.kts`.

### Сценарии и smoke-тесты на Robolectric

Тесты в `app/src/test` и `app/src/testDebug` запускают всё приложение на Robolectric с настоящим графом Hilt и базой:

- `AppSmokeTest` проходит онбординг и основные экраны на профиле телефона;
- `WideScreenTest` открывает широкое окно: боковая панель, список и карточка рядом;
- `ScenariosTest` проходит ключевые сценарии 1–5 из [плана, §22.3](docs/tech-plan.md#223-ключевые-сценарии): «Поделиться» из Telegram, утренний план из уведомления, перегрузка и «Все на завтра», пауза с заметкой и возврат через три дня, застрявшая задача и разбиение на шаги. Часы приложения закреплены на рабочем понедельнике и сдвигаются по дням. Сценарии 6 и 7 проверяет `AutomationTest` в `core:data`.

```bash
./gradlew :app:testGithubDebugUnitTest
```

Скриншоты экранов сохраняются в `app/build/screenshots`: от `01-onboarding-hours.png` до `10-ai-on.png` и от `wide-01-today.png` до `wide-04-tasks.png`. Их смотрят глазами, с эталоном они не сравниваются.

### Рендер виджета

```bash
./gradlew :widget:testDebugUnitTest --tests app.tasker.widget.WidgetRenderTest
```

Тест сохраняет картинки виджета в `widget/build/screenshots`: `widget-plan.png`, `widget-candidates.png`, `widget-locked.png`, `widget-empty.png`, `widget-compact.png` и `widget-plan-dark.png`. С эталоном они тоже не сравниваются.

### Скриншот-тесты компонентов (Roborazzi)

`ComponentScreenshotTest` в `core:ui` снимает общие компоненты в светлой и тёмной теме, на трёх языках и со шрифтом 200%. Эталоны лежат в `core/ui/src/test/screenshots` и проходят ревью в PR.

```bash
./gradlew verifyRoborazziDebug             # сравнить с эталонами, как в CI
./gradlew :core:ui:recordRoborazziDebug    # перезаписать эталоны после намеренного изменения
```

### E2E-тесты на устройстве

Инструментальные тесты лежат в `app/src/androidTest`:

- `FirstStartE2eTest` — онбординг, первая задача и четыре вкладки;
- `QuickCaptureE2eTest` — задача из быстрого ввода виджета за два касания и текст.

```bash
./gradlew :app:connectedGithubDebugAndroidTest   # на подключённом телефоне или эмуляторе
./gradlew :app:nightlyGroupGithubDebugAndroidTest # на управляемых эмуляторах API 26, 33 и 36, как ночью в CI
```

Каждый тест запускается на чистом приложении (Android Test Orchestrator, `clearPackageData`).

### Управляемые часы в debug-сборке

В debug-сборке время приложения можно сдвинуть вперёд и проверить сценарии «через N дней». Команду принимает только adb shell:

```bash
adb shell am broadcast -a app.tasker.debug.SHIFT_CLOCK --ei days 3 --ei hours 2 -p io.github.oxnaezo.tasks
adb shell am broadcast -a app.tasker.debug.SHIFT_CLOCK --ez reset true -p io.github.oxnaezo.tasks
```

Сдвиги складываются; минуты задаются через `--ei minutes N`. После сдвига перезапустите приложение, чтобы прошла догоняющая обработка. Будильники по-прежнему ставятся по времени устройства. В релизной сборке сдвига нет.

### Baseline Profile и замер старта

```bash
./gradlew :app:generateBaselineProfile
./gradlew :baselineprofile:pixel6Api34GithubBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
```

Первая команда собирает профиль на управляемом эмуляторе Pixel 6 с API 34 и кладёт его в `app/src/main/generated/baselineProfiles`. Вторая меряет холодный старт (`StartupBenchmark`) на том же эмуляторе. Ночной workflow делает то же самое и предлагает изменённый профиль отдельным PR; флаги для машин без GPU — в `nightly.yml`. На эмуляторе цифры показывают только тренд: бюджет 1,5 с проверяют на эталонном телефоне. Профиля в репозитории пока нет, замеров на телефоне не было ([спайк S5](docs/spikes/README.md)).

## Каналы и типы сборки

Каналы — флейворы измерения `channel`:

| Флейвор | Для чего | ИИ |
| --- | --- | --- |
| `github` | Android Studio и APK из GitHub Releases | напрямую в OpenRouter со своим ключом пользователя |
| `play` | Google Play | через ИИ-прокси. Адрес задаёт `tasker.aiProxyUrl`, без него ИИ в этой сборке недоступен |

Типы сборки:

| Тип | Что отличается |
| --- | --- |
| `debug` | управляемые часы (`app/src/debug`); в канале `play` можно передать dev-ключ прокси `tasker.aiProxyDevKey` |
| `release` | R8 и сжатие ресурсов; подпись — если заданы переменные ключа (см. «Подпись релиза») |

У всех вариантов один `applicationId` — `io.github.oxnaezo.tasks` ([ADR 0001](docs/adr/0001-neutral-application-id.md)). Как каналы получают доступ к ИИ — в [ADR 0006](docs/adr/0006-ai-access-by-install-channel.md).

## Свойства Gradle

Свойства передают через `-P` или задают в `~/.gradle/gradle.properties`:

| Свойство | По умолчанию | Что задаёт |
| --- | --- | --- |
| `tasker.versionCode` | `1` | `versionCode` |
| `tasker.versionName` | `0.1.0` | `versionName` |
| `tasker.sentryDsn` | пусто | DSN Sentry. Без него отчёты о сбоях выключены ([ADR 0004](docs/adr/0004-crash-reports-sentry.md)) |
| `tasker.playCloudProject` | `0` — не задан | Номер проекта Google Cloud для Play Integrity. Используется только в канале `play` |
| `tasker.aiProxyUrl` | пусто | Адрес ИИ-прокси для канала `play`, только `https://`: через него идут ИИ и недельная статистика |
| `tasker.aiProxyDevKey` | пусто | Ключ dev-окружения прокси вместо Play Integrity. Попадает только в сборку `debug` |

Пример: `./gradlew :app:assemblePlayDebug -Ptasker.aiProxyUrl=https://… -Ptasker.aiProxyDevKey=…`.

## Подпись релиза

Ключ подписи релизов в репозиторий не попадает. По плану его хранят в секретах CI и в резервной копии у владельца ([план, §24.3](docs/tech-plan.md#243-подпись)). `.gitignore` не пускает в git файлы `*.jks`, `*.keystore` и `keystore.properties`.

Сборка `release` подписывается, только если заданы переменные окружения:

| Переменная | Что это |
| --- | --- |
| `TASKER_KEYSTORE_FILE` | путь к файлу хранилища ключей |
| `TASKER_KEYSTORE_PASSWORD` | пароль хранилища |
| `TASKER_KEY_ALIAS` | имя ключа |
| `TASKER_KEY_PASSWORD` | пароль ключа |

Без `TASKER_KEYSTORE_FILE` релизный APK остаётся неподписанным. Если путь задан, нужны и остальные три переменные, иначе сборка не сконфигурируется.

В CI хранилище лежит в секрете `TASKER_KEYSTORE_BASE64`. Workflow `release.yml` раскодирует его во временный файл и подписывает им оба канала. APK, подписанный другим ключом, не встанет поверх установленного: приложение придётся удалить вместе с данными.

## CI

Workflows лежат в `.github/workflows/`:

| Workflow | Когда | Что делает |
| --- | --- | --- |
| `ci.yml` | каждый PR и push в `main` | `ktlintCheck detekt`, `lint`, `unitTests`, `verifyRoborazziDebug`; проверяет, что схемы Room закоммичены; собирает debug обоих каналов и Docker-образ backend |
| `nightly.yml` | каждую ночь и вручную | e2e на управляемых эмуляторах API 26, 33 и 36; генерация Baseline Profile и замер холодного старта; изменённый профиль приходит отдельным PR |
| `release.yml` | тег `v*` | подписанный APK канала `github` в GitHub Releases; если настроен доступ к Google Play — бандл `play` во внутренний трек; образ backend в `ghcr.io` и деплой, если задан хук |

В релизе `versionName` берётся из тега без `v`, а `versionCode` равен 1000 плюс номер запуска workflow — один счётчик для обоих каналов. Нужные секреты перечислены в шапке `release.yml`.

## Установка на телефон

Порядок из [плана, §24.2](docs/tech-plan.md#242-установка-на-телефон):

1. **Разработка.** Android Studio или ADB, по кабелю или по Wi-Fi (Android 11+): `./gradlew :app:installGithubDebug`.
2. **Личное использование.** Подписанный APK из GitHub Releases: открыть страницу релиза на телефоне и установить. Релиз появляется после тега `vX.Y.Z`. Обновления отслеживает Obtainium. Один раз нужно разрешить браузеру или Obtainium устанавливать приложения.
3. **Бета и публикация** — через Google Play, с этапа M10.

Debug- и release-сборки подписаны разными ключами, поэтому одна не встанет поверх другой. Перед переходом сделайте экспорт: «Настройки → Данные → Экспорт».

## ИИ-прокси (backend)

Модуль `:backend` — небольшой сервис на Ktor с PostgreSQL для сборок из Google Play. Он проверяет токен Play Integrity, выдаёт токен установки и выполняет типизированные маршруты ИИ; в MVP работает только `/v1/enrich`. Ещё он принимает недельные агрегаты метрик (`/v1/metrics`). Промпты и проверка ответов общие с приложением (`core:ai-contract`), модель вызывается через OpenRouter (`core:ai-openrouter`). Тексты задач сервис не хранит и не пишет в журналы. Сборкам из GitHub и Android Studio он не нужен. Запуск, переменные окружения и развёртывание описаны в [backend/README.md](backend/README.md).

## Телеметрия

Отчёты о сбоях и недельная статистика уходят с телефона только с согласия. Это один переключатель в «Настройки → Приватность», по умолчанию он выключен. Подпись говорит, что именно отправит сборка. Если отправлять нечего, переключателя нет.

- **Отчёты о сбоях** — Sentry, только в сборке с `tasker.sentryDsn`. Перед отправкой из отчёта вырезается всё, что может содержать текст пользователя: остаются тип исключения и стек ([ADR 0004](docs/adr/0004-crash-reports-sentry.md)).
- **Недельная статистика** — только в сборке с адресом ИИ-прокси, то есть в канале `play`. Телефон сам считает итоги закончившейся недели: время на обслуживание, долю выполненных пунктов плана, переносы, каналы захвата, архив по TTL, дни активности. Раз в сутки, когда есть сеть, приложение отправляет на `/v1/metrics` итоги недель, которые ещё не ушли, — только числа, без текстов ([ADR 0010](docs/adr/0010-weekly-metrics.md), [план, §23](docs/tech-plan.md#23-метрики-и-телеметрия)).

Согласие на ИИ — отдельное и на телеметрию не влияет.

## Оценка качества ИИ

`tools/ai-eval` прогоняет маршрут enrich по набору формулировок и пишет отчёт в `docs/ai-eval/`. Каждый вызов стоит денег, поэтому полный прогон запускают вручную после смены промпта или модели:

```bash
./gradlew :tools:ai-eval:test                                         # проверка набора без вызовов API
OPENROUTER_API_KEY=sk-or-... ./gradlew :tools:ai-eval:run --args="--limit 20"
```

Набор, метрики и параметры описаны в [docs/ai-eval/README.md](docs/ai-eval/README.md). Эталонные ответы в наборе пока черновые.

## Правила кода

- **Время — только через `TimeSource` и `DayClock`.** Правило detekt `ForbiddenClockCall` запрещает `LocalDate.now()`, `Instant.now()`, `System.currentTimeMillis()` и подобные вызовы. Системные часы читает только `SystemTimeSource`. «Сегодня» считает `DayClock` с границей логического дня (по умолчанию 04:00).
- **Фичи независимы.** Модули `feature:*` не зависят друг от друга. Переходы между экранами собирает `app` в `ui/AppEntries.kt`. База доступна только через `core:data`.
- **Любое изменение данных — команда** в `core:data` ([ADR 0005](docs/adr/0005-commands-and-event-log.md)). Действия правил, ИИ и интеграций не считаются касанием и всегда пишут код причины.
- **Красный — только для просрочки.** Правило detekt `OverdueColorOnly` запрещает цвета ошибки Material (`colorScheme.error` и родственные). Для просрочки есть токен `TaskerTheme.colors.overdue`.
- **Строки — на трёх языках:** английский в `values`, русский в `values-ru`, украинский в `values-uk`. Непереведённая строка — ошибка Lint (`MissingTranslation`).
- detekt не пропускает ни одного замечания (`maxIssues: 0`). Длина строки — до 140 символов (`.editorconfig`, `config/detekt/detekt.yml`).

## Что не сделано

- **Замеров на телефоне нет:** холодный старт, время от тапа до клавиатуры, скорость разбора и поиска. Ночной замер старта идёт на эмуляторе и показывает только тренд. Baseline Profile в репозитории ещё нет. Подробности — в [спайках](docs/spikes/README.md).
- **Эталонное устройство** для бюджетов производительности (план, §5) в репозитории не зафиксировано.
- **E2E-тестов на устройстве два:** первый запуск и быстрый ввод. Ключевые сценарии из [плана, §22.3](docs/tech-plan.md#223-ключевые-сценарии) проходят на Robolectric: 1–5 — `ScenariosTest`, 6 и 7 — `AutomationTest`. На эмуляторах их нет. Режим полёта и выключенный ИИ отдельными прогонами не проверяются.
- **Скриншоты с эталоном** сравниваются только для общих компонентов `core:ui`. Снимки экранов из smoke-тестов и виджета сохраняются без сравнения.
- **Голосовой ввод** — только системный диалог распознавания. Проверка и загрузка офлайн-пакетов языков и голосовая заметка к паузе (EXC-3) отложены решением владельца продукта.
- **Политика конфиденциальности и ответы для Data safety** — черновики. Им нужны данные владельца, условия хранения у OpenRouter, поставщиков модели и Sentry и проверка юристом; опубликованной политики нет.
- **Статистика из канала `github`** не отправляется: без Play Integrity у установки нет токена для backend ([ADR 0010](docs/adr/0010-weekly-metrics.md)).
