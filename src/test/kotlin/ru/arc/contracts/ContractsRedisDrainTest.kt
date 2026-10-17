package ru.arc.contracts

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.paper.api.ArcRedisLease
import ru.arc.paper.api.ArcRedisService
import ru.arc.paper.redis.PaperRedisClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ContractsRedisDrainTest {
    @Test
    fun `late prepare retains before host stop and terminal acknowledgement precedes lease close`() {
        val retained = AtomicBoolean()
        val stopped = AtomicBoolean()
        val closed = AtomicBoolean()
        val lease = mockk<ArcRedisLease>(relaxed = true)
        every { lease.retainDuringShutdown() } answers { check(!closed.get()); retained.set(true) }
        every { lease.init() } answers { check(retained.get()) }
        every { lease.publish(any(), any()) } answers {
            check(!closed.get() && (!stopped.get() || retained.get()))
        }
        every { lease.close() } answers { closed.set(true) }
        val service = mockk<ArcRedisService>()
        every { service.acquirePrimary("ArcContracts") } returns lease
        val worker = BoundedAsyncExecutor("contracts-drain-test", 1, 8)
        val acquired = CountDownLatch(1)
        val publishPrepared = CountDownLatch(1)
        val terminalStarted = CountDownLatch(1)
        val acknowledge = CountDownLatch(1)
        var client: PaperRedisClient? = null
        try {
            worker.submit {
                val candidate = openContractsRedis(service)
                acquired.countDown()
                check(publishPrepared.await(5, TimeUnit.SECONDS))
                client = candidate // Accepted preparation may publish after disable has returned.
            }
            assertTrue(acquired.await(5, TimeUnit.SECONDS))
            val terminal = worker.closeAsync {
                requireNotNull(client).publish("arc.contracts.final", "durable-receipt")
                terminalStarted.countDown()
                check(acknowledge.await(5, TimeUnit.SECONDS))
                requireNotNull(client).close()
            }
            stopped.set(true)
            if (!retained.get()) lease.close() // Shared host closes ordinary unretained leases at stop.
            assertFalse(terminal.isDone)
            assertFalse(closed.get())
            publishPrepared.countDown()
            assertTrue(terminalStarted.await(5, TimeUnit.SECONDS))
            assertFalse(closed.get())
            assertFalse(terminal.isDone)
            acknowledge.countDown()
            terminal.get(5, TimeUnit.SECONDS)
            assertTrue(closed.get())
            verify(exactly = 1) { lease.publish("arc.contracts.final", "durable-receipt") }
            verify(exactly = 1) { lease.close() }
            assertEquals(true, retained.get())
        } finally {
            publishPrepared.countDown()
            acknowledge.countDown()
            worker.closeAsync().get(5, TimeUnit.SECONDS)
        }
    }
}
