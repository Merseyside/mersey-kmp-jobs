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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * The runtime of long-running jobs.
 *
 * Keeps a coroutine scope of its own, tied to no screen, and one process hold
 * for all jobs at once: while at least one of them is running, the system is
 * asked not to kill the app.
 *
 * @param specs every kind of work the app is able to perform. The list is
 * needed in full at creation time: after a restart a job is recreated by the
 * name of its kind, and an unregistered one would have nobody to revive it.
 * @param storage where the progress goes. In memory by default, that is until
 * the app is closed for the first time.
 * @param hold what holds the process. Nothing by default.
 */
class RealJobRunner(
    specs: List<JobSpec<*, *>>,
    private val storage: JobStorage = InMemoryJobStorage(),
    private val hold: ProcessHold = NoProcessHold,
    private val json: Json = Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : JobRunner {

    private val specs: Map<String, JobSpec<*, *>> = specs.associateBy(JobSpec<*, *>::type)

    /** One lock for the whole registry: two identical starts must not diverge. */
    private val mutex = Mutex()

    private val jobs = mutableMapOf<JobId, RunningJob<*>>()

    init {
        // The system is taking the time away from the background process — we wrap
        // up without waiting for the app to be killed along with an unsaved step
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

                when {
                    // Waiting for the next attempt — a request to start the very
                    // same work means "try now": the person pressed "retry" and has
                    // no reason to stand until the pause is over
                    typed.isWaiting -> typed.wakeUp()

                    // Unfinished but stopped is a job whose time the system took
                    // away. We continue the very same one instead of starting a
                    // second
                    !typed.isAlive -> launchJob(spec, params, typed)
                }

                return@withLock typed
            }

            // A finished job with the same params is no longer needed: its result
            // has already been taken, and keeping it forever grows the registry
            jobs.values
                .filter { job -> job.type == spec.type && job.params == encodedParams }
                .forEach { job -> jobs.remove(job.id) }

            // Familiar work picks up its past steps: what is done is not redone
            val known = storage.findJob(spec.type, encodedParams)

            // Unless its undo never finished: those steps describe work that has
            // been taken back, so the leftover record goes and we start clean
            if (known?.isCompensating == true) storage.removeJob(known.id)

            val id = known?.takeIf { record -> !record.isCompensating }?.id ?: JobId.random()

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

    override suspend fun <P : Any, R : Any> ongoing(spec: JobSpec<P, R>): List<OngoingJob<P, R>> =
        mutex.withLock {
            jobs.values
                .filter { job -> job.type == spec.type && !job.state.value.isFinished }
                .map { job ->
                    OngoingJob(
                        params = json.decodeFromString(spec.paramsSerializer, job.params),
                        handle = job.cast()
                    )
                }
        }

    override suspend fun cancel(id: JobId) {
        val job = mutex.withLock { jobs[id] } ?: return

        job.coroutine?.cancelAndJoin()

        // What the work has already written is undone here too: for the database
        // there is no difference between a refusal and a change of mind — either
        // way the work did not happen
        withContext(NonCancellable) {
            compensate(specs[job.type], job.params, id, JobCancelledException(id))
        }

        mutex.withLock {
            job.markCancelled()
            jobs.remove(id)
        }

        releaseHoldIfIdle()
    }

    override suspend fun restore() {
        // The undos left halfway go first: until the database is back as it was,
        // reviving anything on top of made-up rows only makes things worse
        storage.compensatingJobs().forEach { record ->
            compensate(
                spec = specs[record.type],
                encodedParams = record.params,
                id = record.id,
                error = JobUndoResumedException(record.id)
            )
        }

        val records = storage.activeJobs()

        mutex.withLock {
            records.forEach { record ->
                // The kind of work is not registered — there is nobody to revive
                // it. The record stays: the spec may appear in the next app version
                val spec = specs[record.type] ?: return@forEach

                val existing = jobs[record.id]

                if (existing?.isAlive == true) {
                    // A waiting one is hurried up: the app is on the screen again,
                    // and the network has most likely returned with it
                    if (existing.isWaiting) existing.wakeUp()

                    return@forEach
                }

                resume(spec, record)
            }
        }
    }

    /**
     * Starts an interrupted job again. The casts here are unavoidable: the
     * registry keeps kinds of work without their params, while the tie between
     * a kind and its params is set by [JobSpec] itself and cannot be broken.
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

    /**
     * Undoes what the work has written and forgets the job.
     *
     * The mark goes into the storage first: from that moment the record is not
     * revived but undone, and a process killed midway does not lose the undo —
     * the next [restore] picks it up by that very mark. The record is forgotten
     * only after the undo, along with its steps: there is nothing to continue
     * from anymore, what they describe has just been taken back.
     */
    private suspend fun <P : Any, R : Any> compensate(
        spec: JobSpec<P, R>,
        params: P,
        id: JobId,
        error: Throwable
    ) {
        storage.setCompensating(id)
        spec.compensate(params, error)
        storage.removeJob(id)
    }

    /**
     * The same, for a job whose params are only known as stored text. An
     * unregistered kind of work has nobody to undo it — the record is forgotten
     * as it is, there is no one to ask what it wrote.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun compensate(
        spec: JobSpec<*, *>?,
        encodedParams: String,
        id: JobId,
        error: Throwable
    ) {
        if (spec == null) {
            storage.removeJob(id)
            return
        }

        val typedSpec = spec as JobSpec<Any, Any>

        compensate(
            spec = typedSpec,
            params = json.decodeFromString(typedSpec.paramsSerializer, encodedParams),
            id = id,
            error = error
        )
    }

    private fun <P : Any, R : Any> launchJob(
        spec: JobSpec<P, R>,
        params: P,
        job: RunningJob<R>
    ) {
        job.coroutine = scope.launch {
            try {
                // As many attempts as it takes: a temporary error is temporary for
                // a reason. How often to try — and whether to try on our own at all
                // — is decided by the work itself through its RetryPolicy
                while (true) {
                    // The hold of the process is the condition for starting,
                    // not a nicety on top of it: the system refuses it to an
                    // app that is not on the screen, and an attempt begun
                    // without it would be cut short mid-step — together with
                    // the whole app. Refused means we sleep until woken, and
                    // the waking comes from restore(), which the app calls
                    // when it is back on the screen
                    while (!hold.acquire()) {
                        job.markHeldBack()
                        job.awaitWake()
                    }

                    job.markRunning()

                    val outcome = runAttempt(spec, params, job) ?: return@launch

                    // While waiting we release the process hold: keeping a
                    // foreground service with a notification for the sake of sleep
                    // deceives both the system and the person
                    withContext(NonCancellable) { releaseHoldIfIdle() }

                    // A request to start this very work wakes us before the time
                    when (val policy = spec.retryPolicy) {
                        RetryPolicy.OnDemand -> job.awaitWake()

                        is RetryPolicy.Backoff ->
                            withTimeoutOrNull(policy.delayAfter(outcome).millis) { job.awaitWake() }
                    }
                }
            } finally {
                withContext(NonCancellable) { releaseHoldIfIdle(finished = job) }
            }
        }
    }

    /**
     * A single attempt, made with the hold of the process already taken.
     * Returns the number of the failed attempt if the work is worth repeating,
     * and null if everything is over: with success or with a final failure.
     */
    private suspend fun <P : Any, R : Any> runAttempt(
        spec: JobSpec<P, R>,
        params: P,
        job: RunningJob<R>
    ): Int? {
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

            // NonCancellable: the job has already done the work, and its record
            // has to be closed even if the cancellation came at this very moment
            withContext(NonCancellable) {
                storage.removeJob(job.id)
                job.markSuccess(result)
            }

            return null
        } catch (cancellation: CancellationException) {
            // The progress stays in the storage and the record stays active: this
            // is a pause, not an end. Giving the job up is what cancel() does
            throw cancellation
        } catch (error: Throwable) {
            // Nothing can help — we say so at once: repeat as long as you like,
            // the answer stays the same, and the person waits in vain all that time
            if (!spec.isRetryable(error)) {
                withContext(NonCancellable) {
                    // markFailed even if the undo itself failed: the record keeps
                    // its mark and the next restore() will finish the undo, but
                    // whoever waits must not be left waiting forever
                    try {
                        compensate(spec, params, job.id, error)
                    } finally {
                        job.markFailed(error)
                    }
                }

                return null
            }

            // The record stays active: if the process is killed, the job will be
            // revived by the next restore(), the way an interrupted one is
            return withContext(NonCancellable) { job.markWaiting(error) }
        }
    }

    /**
     * Stops everything, leaving the jobs unfinished: the passed steps are
     * already in the storage, and [restore] will continue them from the nearest
     * step not yet passed.
     */
    private suspend fun pauseAll() {
        val working = mutex.withLock { jobs.values.filter(RunningJob<*>::isWorking) }

        working.forEach { job -> job.coroutine?.cancelAndJoin() }
    }

    /**
     * Releases the hold once there is nothing left to work on.
     *
     * @param finished the job that is playing out its very last step right now.
     * It has to be excluded: the call comes from its own coroutine, and that one
     * stays active until it returns — the job would see itself as running, and
     * the hold would never be released.
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
     * A job in the registry: its state for the observers and its coroutine.
     */
    private class RunningJob<R : Any>(
        override val id: JobId,
        val type: String,
        val params: String
    ) : JobHandle<R> {

        private val mutableState = MutableStateFlow<JobState<R>>(JobState.Running(progress = null))

        override val state: StateFlow<JobState<R>> = mutableState.asStateFlow()

        /** Steps without the storage. They survive a job restart, but not a process one. */
        val memory = mutableMapOf<String, Any?>()

        var coroutine: Job? = null

        /**
         * A request to try right now instead of sleeping the pause out. A buffer
         * for one request: while the job sleeps, waking it twice is the same as
         * waking it once.
         */
        private val wake = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** How many attempts have already failed. The pause grows by it. */
        private var attempts = 0

        /** The coroutine is alive: either running or sleeping until the next attempt. */
        val isAlive: Boolean
            get() = coroutine?.isActive == true

        /** Waiting for the next attempt. The process is not held for that. */
        val isWaiting: Boolean
            get() = state.value is JobState.Waiting

        /** Running right now — unlike a sleeping one, which does nothing. */
        val isWorking: Boolean
            get() = isAlive && !isWaiting

        fun report(progress: JobProgress) {
            mutableState.value = JobState.Running(progress)
        }

        fun markRunning() {
            mutableState.value = JobState.Running(progress = null)
        }

        /**
         * Marks a job parked until the app is back on the screen: the system
         * refused the hold of the process, and starting without it is worse
         * than waiting.
         *
         * The counter of attempts stays where it was — this attempt has not
         * happened. The state is the very same waiting: for whoever watches
         * the job there is no difference between an obstacle outside and one
         * at home, the work is unfinished and will go on either way.
         */
        fun markHeldBack() {
            mutableState.value =
                JobState.Waiting(error = ProcessHoldRefusedException(id), attempt = attempts)
        }

        /** Marks a failed attempt and returns its number. */
        fun markWaiting(error: Throwable): Int {
            attempts++
            mutableState.value = JobState.Waiting(error = error, attempt = attempts)

            return attempts
        }

        fun wakeUp() {
            wake.tryEmit(Unit)
        }

        suspend fun awaitWake() {
            wake.first()
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
