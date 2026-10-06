# 0005. Изменения только через команды с журналом событий

## Статус

Принято, 2026-10-06.

## Контекст

- ТЗ требует истории задачи, журнала автоматики с причинами (принцип 7, AUT-1), отмены и метрик.
- Срок жизни задачи (TTL) отсчитывается от последнего касания пользователя (TTL-1). Значит, действия правил, ИИ и интеграций не должны выглядеть как касание.
- Побочные эффекты — будильники, виджет, очередь ИИ — не должны тормозить ввод, а их сбой не должен откатывать данные ([план, §4.2](../tech-plan.md#42-принципы)).

## Решение

- **Команда.** Любое изменение данных — команда в `core:data`. `TxRunner.run` выполняет её в одной транзакции Room. Внутри транзакции объект `Tx` меняет сущности, пишет события и обновляет поисковый индекс FTS.
- **Событие.** Событие хранит актора (USER, RULE, AI, INTEGRATION), пакет (`batch_id`) и изменения полей «до → после». Событие не от пользователя без кода причины не создаётся: в `Event` стоит `require(actor == Actor.USER || reason != null)`.
- **Три представления одной таблицы.** Из событий строятся история задачи, журнал автоматики (события с `actor != 'USER'`, `EventDao`) и отмена.
- **Касание.** `lastTouchedAt` двигают только команды пользователя. Для правил, ИИ и интеграций `Tx.updateTask` сохраняет прежнее значение. Черновик плана дня не пишет ни событий, ни касаний. Принятие плана — не касание, а ручное добавление в план и перестановка — касание (`PlanService`).
- **Отмена.**
  - Команду пользователя можно отменить в течение 15 минут, действие автоматики — в течение 30 дней (`Tx.USER_UNDO_WINDOW`, `Tx.AUTOMATION_UNDO_WINDOW`).
  - `UndoService` откатывает пакет по его событиям. Поля, которые пользователь менял после события, остаются как есть и возвращаются списком конфликтов.
  - Служебные метки правил не откатываются, иначе правило сработало бы снова. Пункты планов прошлых дней не меняются.
- **Эффекты после коммита.** `CommitEffects.dispatch` запускает слушателей `CommitListener` в области приложения. Медленный эффект не задерживает команду, упавший — не отменяет её. Слушатели:
  - `ReminderCommitListener` (`core:scheduling`) — напоминания о дедлайнах;
  - `EnrichmentCommitListener` (`core:ai`) — очередь ИИ;
  - `TodayWidgetUpdater` (`widget`) — виджет.
- **База закрыта от фич.** `core:data` подключает `core:database` как `implementation`, поэтому модули, которые зависят от `core:data`, не видят DAO. Convention-плагин фич (`AndroidFeatureConventionPlugin.kt`) подключает только core-модули, и ни одна фича не зависит от `core:database` напрямую.

Где в коде: [`TxRunner.kt`](../../core/data/src/main/kotlin/app/tasker/core/data/command/TxRunner.kt), [`Tx.kt`](../../core/data/src/main/kotlin/app/tasker/core/data/command/Tx.kt), [`UndoService.kt`](../../core/data/src/main/kotlin/app/tasker/core/data/command/UndoService.kt), [`CommitEffects.kt`](../../core/data/src/main/kotlin/app/tasker/core/data/effects/CommitEffects.kt), [`Event.kt`](../../core/model/src/main/kotlin/app/tasker/core/model/Event.kt).

## Последствия

- Новая операция с данными — это новая команда. Фичи не видят DAO и пишут в базу только через команды. Сборка это не проверяет: правило держится на структуре модулей и ревью.
- История, журнал автоматики и отмена одинаково работают для всех команд.
- Эффект может не выполниться, например если процесс убит сразу после коммита. Поэтому `AppStartup` при каждом старте процесса заново планирует будильники и догоняющую обработку и возобновляет очередь ИИ.
- Таблица событий растёт с каждым изменением. План оценивает 10–20 событий на задачу ([§7.5](../tech-plan.md#75-журнал-событий-event)).
- Инварианты закреплены тестами `core:data`:
  - `AutomationTest`: «automation never moves last touched, user commands always do (TTL-1)», «undone automation is not repeated by the next catch-up», «undo keeps fields the user changed since and reports them»;
  - `AiAndUndoPropertyTest`: «ai fills only empty fields, never touches the task and can be undone»;
  - `CommandsTest`: «accepting the plan is not a touch».
