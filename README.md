# mersey-kmp-jobs

Долгие задачи для Kotlin Multiplatform: **Android, iOS, Web (Wasm)**.

Работа, которая идёт минутами, не должна зависеть от экрана, с которого её запустили,
и не должна начинаться заново после того, как систему решила выгрузить приложение.
Библиотека даёт для этого рантайм; что именно считается внутри задачи, она не знает.

## Что решает

Обычная корутина экрана умирает вместе с экраном. Перенести её в область уровня
приложения мало: приложение уходит в фон, и система вправе убить процесс. Поэтому нужны
три вещи сразу — работа вне экрана, просьба к системе не убивать процесс, и сохранённый
прогресс на случай, если убили всё-таки.

Прогресс сохраняется **пошагово**. Незавершённый запрос продолжить невозможно, его можно
только повторить — но повторяется именно он, а не всё, что было сделано до него.

## Что внутри

```
jobs-core/
├── commonMain/
│   ├── JobSpec.kt             вид работы: имя, параметры, тело
│   ├── JobScope.kt            разметка работы на шаги + доклад о ходе
│   ├── JobRunner.kt           запуск, наблюдение, отмена, восстановление
│   ├── RealJobRunner.kt       рантайм: область корутин, реестр, удержание процесса
│   ├── RealJobScope.kt        контрольные точки шагов
│   ├── JobHandle.kt           ручка запущенной задачи + await()
│   ├── JobState.kt            Running | Success | Failed | Cancelled
│   ├── JobId.kt, JobProgress.kt
│   ├── storage/               интерфейс хранилища + реализация в памяти
│   └── hold/ProcessHold.kt    интерфейс удержания процесса + заглушка
├── androidMain/               сервис на переднем плане (dataSync)
└── iosMain/                   beginBackgroundTask
```

Приложение приносит две вещи: **хранилище** (у него уже есть база) и **удержание процесса**
под свою платформу. Своей базы библиотека не заводит и своих строк для уведомления не имеет.

## Как пишется задача

```kotlin
object SortLibraryJob : JobSpec<SortParams, SortResult> {

    override val type = "sort-library"
    override val paramsSerializer = SortParams.serializer()

    override suspend fun JobScope.execute(params: SortParams): SortResult {
        val playlists = step("playlists") { api.playlists(params.userId) }

        val tracks = playlists.flatMapIndexed { index, playlist ->
            // Имя шага должно совпадать от прохода к проходу — отсюда номер
            step("tracks-$index") { api.tracks(playlist.id) }
        }

        // Данные каталога на диск не ложатся: поставщик разрешает держать их
        // только на время показа
        val genres = memoryStep("genres") { beatport.genresFor(tracks) }

        report(SortProgress.Building)
        return SortResult(group(tracks, genres))
    }
}
```

Шаг `step` выполняется один раз за всю жизнь задачи: при повторном проходе его результат
приходит из хранилища. Шаг `memoryStep` переживает перезапуск задачи, но не процесса.

## Запуск

```kotlin
val runner = RealJobRunner(
    specs = listOf(SortLibraryJob),
    storage = MyDatabaseJobStorage(db),
    hold = AndroidProcessHold(context, JobNotification(...))   // на iOS — IosProcessHold()
)

val handle = runner.start(SortLibraryJob, SortParams(userId))
handle.state.collect { state -> /* показать прогресс */ }
```

Повторный `start` с теми же параметрами не заводит вторую задачу — возвращает ту же ручку.
Экран, открытый заново, забирает её по `JobId` через `runner.handle(id)`.

`runner.restore()` вызывается при старте приложения и при возвращении его на экран: он
поднимает работу, прерванную перезапуском или системой.

## Что даёт каждая платформа

| | чем удерживается процесс | сколько живёт |
|---|---|---|
| Android | сервис на переднем плане, тип `dataSync` | пока идут задачи; с Android 15 у `dataSync` есть суточный лимит |
| iOS | `beginBackgroundTask` | десятки секунд после ухода в фон, дальше — пауза до возвращения |
| Wasm | ничего | пока открыта вкладка |

Android 12 и новее не позволяют поднять сервис на переднем плане из фона: `start` и
`restore` вызываются, когда приложение на экране. Уведомление на Android 13 и новее
покажется только с разрешением `POST_NOTIFICATIONS` — спрашивает его приложение,
без разрешения сервис работает молча.
