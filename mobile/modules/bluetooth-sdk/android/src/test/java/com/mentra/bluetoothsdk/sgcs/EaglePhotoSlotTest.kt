package com.mentra.bluetoothsdk.sgcs

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class EaglePhotoSlotTest {

    /** Runs nothing by itself; the test fires pending tasks. */
    private class FakeScheduler : EaglePhotoSlot.Scheduler {
        val scheduled = mutableListOf<Pair<Long, Runnable>>()
        val cancelled = mutableListOf<Runnable>()

        override fun schedule(delayMs: Long, task: Runnable) {
            scheduled += delayMs to task
        }

        override fun cancel(task: Runnable) {
            cancelled += task
            scheduled.removeAll { it.second === task }
        }

        fun fireAll() {
            val tasks = scheduled.map { it.second }
            scheduled.clear()
            tasks.forEach { it.run() }
        }
    }

    private val scheduler = FakeScheduler()
    private val slot = EaglePhotoSlot<String>(scheduler, timeoutMs = 15_000L)
    private val timedOut = mutableListOf<String>()

    @Test
    fun `only one photo is in flight at a time`() {
        assertThat(slot.begin("first") { timedOut += it }).isTrue()
        assertThat(slot.isBusy).isTrue()
        assertThat(slot.begin("second") { timedOut += it }).isFalse()
        // The rejected request started no timer of its own.
        assertThat(scheduler.scheduled).hasSize(1)

        assertThat(slot.take()).isEqualTo("first")
        assertThat(slot.isBusy).isFalse()
        assertThat(slot.begin("third") { timedOut += it }).isTrue()
        assertThat(slot.take()).isEqualTo("third")
    }

    @Test
    fun `capture timeout fails the pending request and frees the slot`() {
        slot.begin("photo-1") { timedOut += it }
        assertThat(scheduler.scheduled.single().first).isEqualTo(15_000L)

        scheduler.fireAll()

        assertThat(timedOut).containsExactly("photo-1")
        assertThat(slot.isBusy).isFalse()
        // A photo arriving after the timeout finds nothing to answer.
        assertThat(slot.take()).isNull()
        assertThat(slot.begin("photo-2") { timedOut += it }).isTrue()
    }

    @Test
    fun `taking the photo cancels its timeout`() {
        slot.begin("photo-1") { timedOut += it }
        val timer = scheduler.scheduled.single().second

        assertThat(slot.take()).isEqualTo("photo-1")

        assertThat(scheduler.cancelled).containsExactly(timer)
        timer.run() // even if the timer was already running
        assertThat(timedOut).isEmpty()
    }

    @Test
    fun `a stale timer does not fail a newer request`() {
        slot.begin("photo-1") { timedOut += it }
        val staleTimer = scheduler.scheduled.single().second
        slot.take()
        slot.begin("photo-2") { timedOut += it }

        staleTimer.run()

        assertThat(timedOut).isEmpty()
        assertThat(slot.take()).isEqualTo("photo-2")
    }

    @Test
    fun `take with nothing pending returns null`() {
        assertThat(slot.take()).isNull()
        assertThat(scheduler.cancelled).isEmpty()
    }
}
