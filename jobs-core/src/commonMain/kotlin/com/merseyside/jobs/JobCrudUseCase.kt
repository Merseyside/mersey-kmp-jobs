package com.merseyside.jobs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One kind of entity a person creates, edits and deletes — a comment, a
 * message — with every change living in the job runtime.
 *
 * An entity exists in two forms, and each change goes its own way for each:
 *
 * - **not sent yet** — the create params waiting in their job. Such an entity is
 *   edited and deleted by changing the job itself: the server never hears about
 *   the versions nobody needs. The `…Creating` calls are for it.
 * - **sent** — the entity the server has. It is edited and deleted through jobs
 *   of its own.
 *
 * The create params are what tells one entity not sent yet from another, so
 * they have to carry something unique of their own — a local id: two entities
 * with the same text would otherwise be one job.
 *
 * @param R the entity as the server returns it.
 * @param C the params of creating it.
 * @param U the params of editing a sent one.
 * @param D the params of deleting a sent one.
 */
abstract class JobCrudUseCase<R : Any, C : Any, U : Any, D : Any>(
    private val createUseCase: JobUseCase<R, C>,
    private val updateUseCase: JobUseCase<R, U>,
    private val deleteUseCase: JobUseCase<*, D>
) {

    /**
     * Starts creating the entity. The course of it comes to [observeCreating],
     * not here: the screen that shows the entity may not be the one that
     * created it.
     */
    fun create(coroutineScope: CoroutineScope, params: C): Job =
        createUseCase.execute(coroutineScope = coroutineScope, params = params)

    /**
     * Every entity being created: those waiting from before and those created
     * later on any screen, for as long as [coroutineScope] lives.
     *
     * @param ownerKey only the entities of this owner — a task, a chat.
     */
    fun observeCreating(
        coroutineScope: CoroutineScope,
        ownerKey: String?,
        onScheduled: (C, Throwable) -> Unit = { _, _ -> },
        onRunning: (C) -> Unit = {},
        onComplete: (C, R) -> Unit = { _, _ -> },
        onError: (C, Throwable) -> Unit = { _, _ -> }
    ): Job = createUseCase.observeOngoing(
        coroutineScope = coroutineScope,
        ownerKey = ownerKey,
        onScheduled = onScheduled,
        onRunning = onRunning,
        onComplete = onComplete,
        onError = onError
    )

    /** Edits a sent entity. */
    fun update(
        coroutineScope: CoroutineScope,
        params: U,
        onComplete: (R) -> Unit = {}
    ): Job = updateUseCase.execute(
        coroutineScope = coroutineScope,
        params = params,
        onComplete = onComplete
    )

    /**
     * Edits an entity not sent yet: [new] takes the place of [old] in the job,
     * and the entity keeps its place in the queue. The new job comes to
     * [observeCreating] as any other.
     *
     * Nothing happens if the entity is already on its way to the server.
     */
    fun updateCreating(coroutineScope: CoroutineScope, old: C, new: C): Job =
        coroutineScope.launch { createUseCase.replace(old = old, new = new) }

    /** Deletes a sent entity. */
    fun delete(
        coroutineScope: CoroutineScope,
        params: D,
        onComplete: () -> Unit = {}
    ): Job = deleteUseCase.execute(
        coroutineScope = coroutineScope,
        params = params,
        onComplete = { onComplete() }
    )

    /**
     * Deletes an entity not sent yet: its job is given up, the server hears
     * nothing.
     *
     * @param onDeleted not called if the entity is already on its way to the
     * server: then it ends as any sent one.
     */
    fun deleteCreating(
        coroutineScope: CoroutineScope,
        params: C,
        onDeleted: () -> Unit = {}
    ): Job = coroutineScope.launch {
        if (createUseCase.cancel(params)) onDeleted()
    }

    /**
     * Sends again an entity the server refused — for a kind of work that
     * [keeps failed jobs][JobSpec.keepsFailed]. It goes on under its own job and
     * keeps its place in the queue.
     */
    fun retryCreating(coroutineScope: CoroutineScope, params: C): Job =
        createUseCase.execute(coroutineScope = coroutineScope, params = params)
}
