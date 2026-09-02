package com.merseyside.jobs

import com.merseyside.jobs.hold.NoProcessHold
import com.merseyside.jobs.hold.ProcessHold
import com.merseyside.jobs.storage.InMemoryJobStorage
import com.merseyside.jobs.storage.JobRecord
import com.merseyside.jobs.storage.JobStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Рантайм долгих задач.
 *
 * Держит свою область корутин, не связанную ни с одним экраном, и одно
 * удержание процесса на все задачи разом: пока работает хоть одна, система
 * просят не убивать приложение.
 *
 * @param specs все виды работ, какие приложение умеет выполнять. Список нужен
 * целиком при создании: после перезапуска задача воссоздаётся по имени вида,
 * и незарегистрированную поднять будет некому.
 * @param storage куда ложится прогресс. По умолчанию — память, то есть до
 * первого закрытия приложения.
 * @param hold чем удерживается процесс. По умолчанию — ничем.
 */
class RealJobRunner(
    specs: List<JobSpec<*, *>>,
    private val storage: JobStorage = InMemoryJobStorage(),
    private val hold: ProcessHold = NoProcessHold,
    private val json: Json = Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : JobRunner {

    private val specs: Map<String, JobSpec<*, *>> = specs.associateBy(JobSpec<*, *>::type)

    /** Один замок на весь реестр: два одинаковых запуска не должны разойтись. */
    private val mutex = Mutex()

    private val jobs = mutableMapOf<JobId, RunningJob<*>>()

    init {
        // Система отбирает время у фонового процесса — сворачиваемся, не дожидаясь,
        // пока приложение убьют вместе с несохранённым шагом
        scope.launch {
            hold.expirations.collect { pauseAll() }
        }
    }

    override suspend fun <P : Any, R : Any> start(spec: JobSpec<P, R>, params: P): JobHandle<R> {
        val encodedParams = json.encodeToString(spec.paramsSerializer, params)

        return mutex.withLock {
            val running = jobs.values.firstOrNull { job ->
                job.type == spec.type &&
                    job.params == encodedParams &&
                    !job.state.value.isFinished
            }

            if (running != null) {
                val typed = running.cast<R>()

                // Незавершённая, но остановленная — это задача, у которой система
                // забрала время. Продолжаем ту же самую, а не заводим вторую
                if (!typed.isWorking) launchJob(spec, params, typed)

                return@withLock typed
            }

            // Отработавшая задача с теми же параметрами больше не нужна: её
            // результат уже забрали, а держать её вечно — растить реестр
            jobs.values
                .filter { job -> job.type == spec.type && job.params == encodedParams }
                .forEach { job -> jobs.remove(job.id) }

            // Знакомая работа поднимает свои прошлые шаги: пройденное не повторяем
            val known = storage.findJob(spec.type, encodedParams)
            val id = known?.id ?: JobId.random()

            storage.saveJob(
                JobRecord(id = id, type = spec.type, params = encodedParams, isActive = true)
            )

            val job = RunningJob<R>(id = id, type = spec.type, params = encodedParams)
            jobs[id] = job
            launchJob(spec, params, job)

            job
        }
    }

    override suspend fun <R : Any> handle(id: JobId): JobHandle<R>? =
        mutex.withLock { jobs[id]?.cast() }

    override suspend fun <P : Any, R : Any> current(spec: JobSpec<P, R>): OngoingJob<P, R>? =
        mutex.withLock {
            val job = jobs.values.firstOrNull { job ->
                job.type == spec.type && !job.state.value.isFinished
            } ?: return@withLock null

            OngoingJob(
                params = json.decodeFromString(spec.paramsSerializer, job.params),
                handle = job.cast()
            )
        }

    override suspend fun cancel(id: JobId) {
        val job = mutex.withLock { jobs[id] } ?: return

        job.coroutine?.cancelAndJoin()

        mutex.withLock {
            storage.removeJob(id)
            job.markCancelled()
            jobs.remove(id)
        }

        releaseHoldIfIdle()
    }

    override suspend fun restore() {
        val records = storage.activeJobs()

        mutex.withLock {
            records.forEach { record ->
                // Вид работы не зарегистрирован — поднять её некому. Запись
                // остаётся: спек может появиться в следующей версии приложения
                val spec = specs[record.type] ?: return@forEach
                if (jobs[record.id]?.isWorking == true) return@forEach

                resume(spec, record)
            }
        }
    }

    /**
     * Заводит прерванную задачу заново. Приведение типов здесь неизбежно:
     * реестр хранит виды работ без своих параметров, а связь между видом и его
     * параметрами задана самим [JobSpec] и нарушиться не может.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun resume(spec: JobSpec<*, *>, record: JobRecord) {
        val typedSpec = spec as JobSpec<Any, Any>
        val params = json.decodeFromString(typedSpec.paramsSerializer, record.params)

        val job = jobs[record.id]?.cast<Any>()
            ?: RunningJob<Any>(id = record.id, type = record.type, params = record.params)
                .also { created -> jobs[record.id] = created }

        launchJob(typedSpec, params, job)
    }

    private suspend fun <P : Any, R : Any> launchJob(
        spec: JobSpec<P, R>,
        params: P,
        job: RunningJob<R>
    ) {
        hold.acquire()

        job.coroutine = scope.launch {
            try {
                val saved = storage.steps(job.id).toMutableMap()

                val jobScope = RealJobScope(
                    jobId = job.id,
                    saved = saved,
                    memory = job.memory,
                    storage = storage,
                    json = json,
                    onProgress = job::report
                )

                val result = with(spec) { jobScope.execute(params) }

                // NonCancellable: задача уже сделала работу, и запись о ней
                // нужно закрыть, даже если отмена пришла в этот самый момент
                withContext(NonCancellable) {
                    storage.removeJob(job.id)
                    job.markSuccess(result)
                }
            } catch (cancellation: CancellationException) {
                // Прогресс остаётся в хранилище, запись — активной: это пауза,
                // а не конец. Отказ от задачи оформляет cancel()
                throw cancellation
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    storage.setActive(job.id, isActive = false)
                    job.markFailed(error)
                }
            } finally {
                withContext(NonCancellable) { releaseHoldIfIdle(finished = job) }
            }
        }
    }

    /**
     * Останавливает всё, оставляя задачи незавершёнными: пройденные шаги уже в
     * хранилище, и [restore] продолжит их с ближайшего непройденного.
     */
    private suspend fun pauseAll() {
        val working = mutex.withLock { jobs.values.filter(RunningJob<*>::isWorking) }

        working.forEach { job -> job.coroutine?.cancelAndJoin() }
    }

    /**
     * Отпускает удержание, когда работать стало нечему.
     *
     * @param finished задача, которая прямо сейчас доигрывает свой последний шаг.
     * Её приходится исключать: вызов приходит из её же корутины, а та до самого
     * возврата остаётся активной — задача увидела бы работающей саму себя, и
     * удержание не сняли бы никогда.
     */
    private suspend fun releaseHoldIfIdle(finished: RunningJob<*>? = null) {
        val idle = mutex.withLock {
            jobs.values.none { job -> job !== finished && job.isWorking }
        }

        if (idle) hold.release()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <R : Any> RunningJob<*>.cast(): RunningJob<R> = this as RunningJob<R>

    /**
     * Задача в реестре: её состояние для наблюдателей и её корутина.
     */
    private class RunningJob<R : Any>(
        override val id: JobId,
        val type: String,
        val params: String
    ) : JobHandle<R> {

        private val mutableState = MutableStateFlow<JobState<R>>(JobState.Running(progress = null))

        override val state: StateFlow<JobState<R>> = mutableState.asStateFlow()

        /** Шаги без хранилища. Переживают перезапуск задачи, но не процесса. */
        val memory = mutableMapOf<String, Any?>()

        var coroutine: Job? = null

        val isWorking: Boolean
            get() = coroutine?.isActive == true

        fun report(progress: JobProgress) {
            mutableState.value = JobState.Running(progress)
        }

        fun markSuccess(result: R) {
            mutableState.value = JobState.Success(result)
        }

        fun markFailed(error: Throwable) {
            mutableState.value = JobState.Failed(error)
        }

        fun markCancelled() {
            mutableState.value = JobState.Cancelled
        }
    }
}
