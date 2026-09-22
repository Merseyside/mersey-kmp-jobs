package com.merseyside.jobs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReplacePreviousMergeTest {

    @Serializable
    data class Edit(val title: String? = null, val date: String? = null)

    private open class EditSpec : JobSpec<Edit, Edit> {
        val gate = CompletableDeferred<Unit>()

        override val type = "edit"
        override val paramsSerializer = Edit.serializer()

        override suspend fun JobScope.execute(params: Edit): Edit {
            gate.await()
            return params
        }
    }

    private class MergingEditSpec : EditSpec() {
        override fun mergeReplaced(previous: Edit, next: Edit) =
            Edit(title = next.title ?: previous.title, date = next.date ?: previous.date)
    }

    @Test
    fun replacedParamsGoUnderTheNewOnes() = runBlocking<Unit> {
        val spec = MergingEditSpec()
        val runner = RealJobRunner(listOf(spec))

        val first = runner.start(spec, Edit(title = "title"), OWNER, replacesPrevious = true)
        val second = runner.start(spec, Edit(date = "date"), OWNER, replacesPrevious = true)
        spec.gate.complete(Unit)

        assertEquals(Edit(title = "title", date = "date"), withTimeout(TIMEOUT) { second.await() })
        assertIs<JobState.Cancelled>(first.state.value)
    }

    @Test
    fun newerFieldWinsOverOlder() = runBlocking<Unit> {
        val spec = MergingEditSpec()
        val runner = RealJobRunner(listOf(spec))

        runner.start(spec, Edit(title = "old", date = "date"), OWNER, replacesPrevious = true)
        val last = runner.start(spec, Edit(title = "new"), OWNER, replacesPrevious = true)
        spec.gate.complete(Unit)

        assertEquals(Edit(title = "new", date = "date"), withTimeout(TIMEOUT) { last.await() })
    }

    @Test
    fun byDefaultOnlyTheLastRequestCounts() = runBlocking<Unit> {
        val spec = EditSpec()
        val runner = RealJobRunner(listOf(spec))

        runner.start(spec, Edit(title = "title"), OWNER, replacesPrevious = true)
        val second = runner.start(spec, Edit(date = "date"), OWNER, replacesPrevious = true)
        spec.gate.complete(Unit)

        assertEquals(Edit(date = "date"), withTimeout(TIMEOUT) { second.await() })
    }

    @Test
    fun otherOwnersAreNotMerged() = runBlocking<Unit> {
        val spec = MergingEditSpec()
        val runner = RealJobRunner(listOf(spec))

        val other = runner.start(spec, Edit(title = "title"), "other", replacesPrevious = true)
        val mine = runner.start(spec, Edit(date = "date"), OWNER, replacesPrevious = true)
        spec.gate.complete(Unit)

        assertEquals(Edit(date = "date"), withTimeout(TIMEOUT) { mine.await() })
        assertEquals(Edit(title = "title"), withTimeout(TIMEOUT) { other.await() })
    }

    private companion object {
        const val OWNER = "task-1"
        const val TIMEOUT = 5_000L
    }
}
