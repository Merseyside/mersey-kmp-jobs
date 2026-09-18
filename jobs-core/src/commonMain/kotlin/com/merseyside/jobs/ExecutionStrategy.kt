package com.merseyside.jobs

/**
 * How jobs of one kind share their time with each other.
 */
sealed interface ExecutionStrategy {

    /** Every job goes on its own, not looking at the others. */
    data object Parallel : ExecutionStrategy

    /**
     * Jobs of this kind go one by one, in the order they were created: the next
     * one does not begin until the one before it is over.
     *
     * For work whose order the server sees — messages sent without the network
     * must arrive the way they were written, not the way the attempts happened
     * to line up.
     *
     * A job standing in the queue shows the state of the one in front of it: if
     * that one waits for the network, so does the whole queue, and for whoever
     * watches there is no difference between the obstacle and the queue.
     *
     * @param onFailure what the final failure of a job does to those behind it.
     * A cancellation is not a failure here: it concerns the cancelled job only.
     */
    data class Sequential(
        val onFailure: QueueFailure = QueueFailure.Continue
    ) : ExecutionStrategy
}

/**
 * What the final failure of a job in the queue does to those standing behind it.
 */
enum class QueueFailure {

    /** The failed job goes alone, the rest take their turn as usual. */
    Continue,

    /**
     * Everyone standing behind fails too, without even trying — for work that
     * makes no sense without the one before it. Each of them is undone and gets
     * [JobQueueFailedException]. A job created after the failure begins a new
     * queue.
     */
    FailRest
}
