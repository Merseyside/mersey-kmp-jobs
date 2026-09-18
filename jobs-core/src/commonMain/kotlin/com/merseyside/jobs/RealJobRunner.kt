package com.merseyside.jobs

import com.merseyside.jobs.hold.NoProcessHold
import com.merseyside.jobs.hold.ProcessHold
import com.merseyside.jobs.storage.InMemoryJobStorage
import com.merseyside.jobs.storage.JobRecord
import com.merseyside.jobs.storage.JobStorage
import com.merseyside.merseyLib.time.Time
import com.merseyside.merseyLib.time.units.Millis
import com.merseyside.merseyLib.time.units.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
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

    /**
     * Every job put into the registry, for those observing a kind of work from
     * now on. Unlimited: an emission happens under [mutex] and must never wait
     * for a slow observer.
     */
    private val registered = MutableSharedFlow<RunningJob<*>>(extraBufferCapacity = Int.MAX_VALUE)

    /**
     * Serializes the starts that replace work: two of them with the same key,
     * each cancelling what it found, would both start and go on side by side.
     * Separate from [mutex], because a cancellation waits for the undo.
     */
    private val replaceMutex = Mutex()

    /** The creation time given out last: the next job never gets the same one. */
    private var lastCreatedAt = 0L

    init {
        // The system is taking the time away from the background process — we wrap
        // up without waiting for the app to be killed along with an unsaved step
        scope.launch {
            hold.expirations.collect { pauseAll() }
        }
    }

    override suspend fun <P : Any, R : Any> start(
        spec: JobSpec<P, R>,
        params: P,
        ownerKey: String?,
        replacesPrevious: Boolean
    ): JobHandle<R> {
        val encodedParams = json.encodeToString(spec.paramsSerializer, params)

        if (ownerKey == null || !replacesPrevious) return register(spec, params, encodedParams, ownerKey)

        return replaceMutex.withLock {
            // The same params are not replaced: that is the very same work asked
            // for again, and it is joined below instead of being started anew
            val replaced = mutex.withLock {
                jobs.values.filter { job ->
                    job.type == spec.type &&
                        job.ownerKey == ownerKey &&
                        job.params != encodedParams &&
                        !job.state.value.isFinished
                }
            }

            replaced.forEach { job -> cancel(job.id) }

            register(spec, params, encodedParams, ownerKey)
        }
    }

    /**
     * @param takenPlace the creation time of a job this one replaces: the new
     * job stands in the queue where the old one stood.
     */
    private suspend fun <P : Any, R : Any> register(
        spec: JobSpec<P, R>,
        params: P,
        encodedParams: String,
        ownerKey: String?,
        takenPlace: TimeUnit? = null
    ): JobHandle<R> {
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

            val continued = known?.takeIf { record -> !record.isCompensating }
            val id = continued?.id ?: JobId.random()

            // A repeated start keeps its place in the queue instead of going to its end
            val createdAt = takenPlace
                ?: continued?.createdAt?.also(::rememberCreatedAt)
                ?: nextCreatedAt()

            storage.saveJob(
                JobRecord(
                    id = id,
                    type = spec.type,
                    params = encodedParams,
                    createdAt = createdAt,
                    ownerKey = ownerKey,
                    isActive = true
                )
            )

            val job = RunningJob<R>(
                id = id,
                type = spec.type,
                params = encodedParams,
                createdAt = createdAt,
                ownerKey = ownerKey
            )
            jobs[id] = job
            registered.tryEmit(job)
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

    override suspend fun <P : Any, R : Any> ongoing(
        spec: JobSpec<P, R>,
        ownerKey: String?
    ): List<OngoingJob<P, R>> =
        mutex.withLock {
            jobs.values
                .filter { job -> job.belongsTo(spec, ownerKey) && job.isOngoing }
                .map { job -> job.toOngoing(spec) }
        }

    override fun <P : Any, R : Any> observe(
        spec: JobSpec<P, R>,
        ownerKey: String?
    ): Flow<OngoingJob<P, R>> = channelFlow {
        // By identity, not by id: a failed job retried is a new handle under the
        // very same id, and whoever watched the failure has to see the retry too
        val seen = mutableSetOf<RunningJob<*>>()
        val seenLock = Mutex()

        suspend fun offer(job: RunningJob<*>) {
            if (seenLock.withLock { seen.add(job) }) send(job.toOngoing(spec))
        }

        // Subscribed before the snapshot is taken: a job registered in between
        // comes twice and is dropped once, instead of never coming at all
        launch(start = CoroutineStart.UNDISPATCHED) {
            registered.collect { job -> if (job.belongsTo(spec, ownerKey)) offer(job) }
        }

        mutex.withLock { jobs.values.filter { job -> job.belongsTo(spec, ownerKey) && job.isOngoing } }
            .forEach { job -> offer(job) }
    }

    override suspend fun cancel(id: JobId) {
        val job = mutex.withLock { jobs[id] } ?: return

        cancelJob(job)
    }

    override suspend fun <P : Any, R : Any> cancelIfIdle(spec: JobSpec<P, R>, params: P): Boolean {
        val job = claimIdle(spec, json.encodeToString(spec.paramsSerializer, params)) ?: return false

        cancelJob(job)

        return true
    }

    override suspend fun <P : Any, R : Any> replace(
        spec: JobSpec<P, R>,
        old: P,
        new: P,
        ownerKey: String?
    ): JobHandle<R>? {
        val encodedOld = json.encodeToString(spec.paramsSerializer, old)
        val encodedNew = json.encodeToString(spec.paramsSerializer, new)

        if (encodedOld == encodedNew) {
            return mutex.withLock {
                jobs.values.firstOrNull { job -> job.type == spec.type && job.params == encodedOld && job.isOngoing }
            }?.cast()
        }

        val replaced = claimIdle(spec, encodedOld) ?: return null

        // The new job comes first, on the very same place: those standing behind
        // the old one must not slip ahead while it is being given up
        val handle = register(spec, new, encodedNew, ownerKey, takenPlace = replaced.createdAt)

        cancelJob(replaced)

        return handle
    }

    /**
     * Finds the job and takes it away from its own coroutine: from here on it
     * does not begin an attempt, even if woken this very moment. Null if the job
     * is in the middle of an attempt or there is no such job.
     */
    private suspend fun claimIdle(spec: JobSpec<*, *>, encodedParams: String): RunningJob<*>? =
        mutex.withLock {
            jobs.values
                .firstOrNull { job -> job.type == spec.type && job.params == encodedParams && job.isOngoing }
                ?.takeIf { job -> !job.isAttempting }
                ?.also { job -> job.isDropped = true }
        }

    private suspend fun cancelJob(job: RunningJob<*>) {
        job.coroutine?.cancelAndJoin()

        // A failed job was undone at the moment of its failure: a second undo
        // would take back what nobody wrote
        val isFailed = job.state.value is JobState.Failed

        withContext(NonCancellable) {
            // What the work has already written is undone here too: for the
            // database there is no difference between a refusal and a change of
            // mind — either way the work did not happen
            if (isFailed) {
                storage.removeJob(job.id)
            } else {
                compensate(specs[job.type], job.params, job.id, JobCancelledException(job.id))
            }
        }

        mutex.withLock {
            job.markCancelled()
            jobs.remove(job.id)
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
        val failed = storage.failedJobs()

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

            // Kept failures come back as they were left: failed, waiting for a
            // person to retry or delete them
            failed.forEach { record ->
                if (specs[record.type]?.keepsFailed != true || record.id in jobs) return@forEach

                rememberCreatedAt(record.createdAt)

                val job = RunningJob<Any>(
                    id = record.id,
                    type = record.type,
                    params = record.params,
                    createdAt = record.createdAt,
                    ownerKey = record.ownerKey
                )
                job.markFailed(JobFailedBeforeRestartException(record.id))

                jobs[record.id] = job
                registered.tryEmit(job)
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

        rememberCreatedAt(record.createdAt)

        val job = jobs[record.id]?.cast<Any>()
            ?: RunningJob<Any>(
                id = record.id,
                type = record.type,
                params = record.params,
                createdAt = record.createdAt,
                ownerKey = record.ownerKey
            ).also { created ->
                jobs[record.id] = created
                registered.tryEmit(created)
            }

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
        error: Throwable,
        keepFailed: Boolean = false
    ) {
        storage.setCompensating(id)
        spec.compensate(params, error)

        if (keepFailed) storage.setFailed(id) else storage.removeJob(id)
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
        error: Throwable,
        keepFailed: Boolean = false
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
            error = error,
            keepFailed = keepFailed
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
                    // The turn comes before the hold: a job standing in the queue
                    // does nothing, and there is no reason to keep the process for it
                    if (spec.executionStrategy is ExecutionStrategy.Sequential) awaitTurn(job)

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

                    // Under the lock: a person giving the job up right now gets
                    // it either before the attempt or not at all
                    val claimed = mutex.withLock {
                        if (!job.isDropped) {
                            job.isAttempting = true
                            job.markRunning()
                        }

                        !job.isDropped
                    }

                    if (!claimed) awaitCancellation()

                    val outcome = try {
                        runAttempt(spec, params, job)
                    } finally {
                        withContext(NonCancellable) { mutex.withLock { job.isAttempting = false } }
                    } ?: return@launch

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
                        compensate(spec, params, job.id, error, keepFailed = spec.keepsFailed)
                    } finally {
                        try {
                            if (spec.failsQueueBehind) failQueueBehind(job, error)
                        } finally {
                            job.markFailed(error)
                        }
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
     * Waits until there is nobody of the same kind created before this job.
     *
     * Only the nearest one in front is watched: it follows the one in front of
     * it the same way, so the state of the head passes down the whole queue.
     * The one in front may finish in any way — then the next one in front is
     * looked for anew.
     */
    private suspend fun awaitTurn(job: RunningJob<*>) {
        while (true) {
            val ahead = mutex.withLock { jobAhead(job) } ?: return

            ahead.state.first { state ->
                job.follow(state)
                state.isFinished
            }
        }
    }

    private fun jobAhead(job: RunningJob<*>): RunningJob<*>? =
        jobs.values
            .filter { other ->
                other.type == job.type &&
                    other.createdAt.millis < job.createdAt.millis &&
                    !other.state.value.isFinished
            }
            .maxByOrNull { other -> other.createdAt.millis }

    /**
     * Brings down everyone standing behind a job that failed for good.
     *
     * Called before the failed job is marked as such: until then those behind
     * it wait for their turn, and none of them slips into the gap. Their
     * coroutines are stopped first, and only then the undo begins — one undo
     * that failed does not leave the rest of the queue alive.
     */
    private suspend fun failQueueBehind(failed: RunningJob<*>, error: Throwable) {
        val behind = mutex.withLock {
            jobs.values.filter { job ->
                job.type == failed.type &&
                    job.createdAt.millis > failed.createdAt.millis &&
                    !job.state.value.isFinished
            }
        }

        behind.forEach { job -> job.coroutine?.cancelAndJoin() }

        var undoError: Throwable? = null

        behind.forEach { job ->
            val dropped = JobQueueFailedException(jobId = job.id, failedJobId = failed.id, cause = error)

            try {
                compensate(
                    spec = specs[job.type],
                    encodedParams = job.params,
                    id = job.id,
                    error = dropped,
                    keepFailed = specs[job.type]?.keepsFailed == true
                )
            } catch (undo: Throwable) {
                if (undoError == null) undoError = undo
            } finally {
                job.markFailed(dropped)
            }
        }

        undoError?.let { undo -> throw undo }
    }

    /** Called under [mutex]. */
    private fun nextCreatedAt(): TimeUnit {
        val millis = maxOf(Time.nowGMT.millis, lastCreatedAt + 1)
        lastCreatedAt = millis

        return Millis(millis)
    }

    /** Called under [mutex]. */
    private fun rememberCreatedAt(createdAt: TimeUnit) {
        lastCreatedAt = maxOf(lastCreatedAt, createdAt.millis)
    }

    private fun RunningJob<*>.belongsTo(spec: JobSpec<*, *>, ownerKey: String?): Boolean =
        type == spec.type && (ownerKey == null || this.ownerKey == ownerKey)

    /** Unfinished, or failed and kept by its kind. */
    private val RunningJob<*>.isOngoing: Boolean
        get() = when (state.value) {
            is JobState.Failed -> specs[type]?.keepsFailed == true
            else -> !state.value.isFinished
        }

    private fun <P : Any, R : Any> RunningJob<*>.toOngoing(spec: JobSpec<P, R>): OngoingJob<P, R> =
        OngoingJob(
            params = json.decodeFromString(spec.paramsSerializer, params),
            handle = cast()
        )

    private val JobSpec<*, *>.failsQueueBehind: Boolean
        get() = (executionStrategy as? ExecutionStrategy.Sequential)?.onFailure == QueueFailure.FailRest

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
        val params: String,
        val createdAt: TimeUnit,
        val ownerKey: String?
    ) : JobHandle<R> {

        private val mutableState = MutableStateFlow<JobState<R>>(JobState.Running(progress = null))

        override val state: StateFlow<JobState<R>> = mutableState.asStateFlow()

        /** Steps without the storage. They survive a job restart, but not a process one. */
        val memory = mutableMapOf<String, Any?>()

        var coroutine: Job? = null

        /** A request is on its way right now. Changed and read under the runner lock only. */
        var isAttempting = false

        /**
         * Given up by a person while idle: the coroutine is about to be
         * cancelled and must not begin an attempt in the meantime. Under the
         * runner lock only.
         */
        var isDropped = false

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

        /**
         * Shows the state of the job in front in the queue: its obstacle is
         * this one's obstacle too. The counter of attempts is our own — this
         * job has not tried anything yet.
         */
        fun follow(ahead: JobState<*>) {
            // Given up and about to be cancelled: its last word must not land
            // after the job that replaced it has spoken
            if (isDropped) return

            mutableState.value = when (ahead) {
                is JobState.Waiting -> JobState.Waiting(error = ahead.error, attempt = attempts)
                else -> JobState.Running(progress = null)
            }
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
