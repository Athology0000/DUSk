package org.phantom.loader.activation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ActivationExecutorTest {

    @Test
    fun `submitted activations run on a single thread in order`() {
        val executor = ActivationExecutor()
        val seen = mutableListOf<Int>()
        val latch = CountDownLatch(3)

        executor.submit { synchronized(seen) { seen.add(1) }; latch.countDown() }
        executor.submit { synchronized(seen) { seen.add(2) }; latch.countDown() }
        executor.submit { synchronized(seen) { seen.add(3) }; latch.countDown() }

        latch.await()
        assertEquals(listOf(1, 2, 3), seen.toList())
        executor.shutdown()
    }

    @Test
    fun `submit returns quickly even when previous task blocks`() {
        val executor = ActivationExecutor()
        val started = AtomicInteger(0)
        val blockStart = CountDownLatch(1)
        val blockEnd = CountDownLatch(1)

        executor.submit {
            started.incrementAndGet()
            blockStart.countDown()
            blockEnd.await() // hold the worker
        }
        blockStart.await()

        val submitDeadline = System.nanoTime() + 200_000_000L // 200ms budget
        executor.submit { started.incrementAndGet() }
        assertTrue(System.nanoTime() < submitDeadline, "submit should be non-blocking")

        blockEnd.countDown()
        executor.shutdown()
    }
}
