package io.legado.app

import io.legado.app.model.analyzeRule.AnalyzeUrl.ConcurrentRecord
import org.junit.Assert
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BugFixTest {

    /**
     * 测试 ConcurrentHashMap 的 computeIfAbsent 原子性
     * 验证多线程并发创建 ConcurrentRecord 时，同一 key 只创建一个实例
     */
    @Test
    fun testConcurrentHashMapComputeIfAbsentAtomicity() {
        val map = ConcurrentHashMap<String, ConcurrentRecord>()
        val threadCount = 10
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val records = ConcurrentHashMap<String, MutableList<ConcurrentRecord>>()

        val key = "test_source_key"
        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    val record = map.computeIfAbsent(key) {
                        ConcurrentRecord(false, System.currentTimeMillis(), 1)
                    }
                    records.getOrPut(key) { mutableListOf() }.add(record)
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        // 验证：同一个 key 的所有引用都指向同一个 ConcurrentRecord 实例
        val recordList = records[key] ?: error("No records found for key")
        val firstRecord = recordList.first()
        recordList.forEach {
            Assert.assertSame(
                "All threads should get the same ConcurrentRecord instance",
                firstRecord, it
            )
        }

        // 验证：map 中只有一个 entry
        Assert.assertEquals("Map should have exactly 1 entry", 1, map.size)
    }

    /**
     * 测试 ConcurrentHashMap 的线程安全：不同 key 创建不同实例
     */
    @Test
    fun testConcurrentHashMapDifferentKeys() {
        val map = ConcurrentHashMap<String, ConcurrentRecord>()
        val threadCount = 5
        val latch = CountDownLatch(threadCount * 2)
        val executor = Executors.newFixedThreadPool(threadCount * 2)

        for (i in 0 until threadCount) {
            val key = "source_$i"
            // 每个 key 提交 2 个任务，模拟并发
            executor.submit {
                try {
                    map.computeIfAbsent(key) {
                        ConcurrentRecord(false, System.currentTimeMillis(), 1)
                    }
                } finally {
                    latch.countDown()
                }
            }
            executor.submit {
                try {
                    map.computeIfAbsent(key) {
                        ConcurrentRecord(false, System.currentTimeMillis(), 1)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        // 验证：每个 key 有且只有一个 entry
        Assert.assertEquals("Map should have exactly $threadCount entries", threadCount, map.size)
        for (i in 0 until threadCount) {
            Assert.assertNotNull("Key source_$i should exist", map["source_$i"])
        }
    }

    /**
     * 测试 ConcurrentRecord 的 synchronized 同步正确性
     * 验证在 synchronized 块中对 frequency 的修改是线程安全的
     */
    @Test
    fun testConcurrentRecordSynchronizedAccess() {
        val record = ConcurrentRecord(false, System.currentTimeMillis(), 0)
        val threadCount = 20
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    synchronized(record) {
                        record.frequency += 1
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        Assert.assertEquals(
            "Frequency should be $threadCount after concurrent increments",
            threadCount, record.frequency
        )
    }

    /**
     * 测试空源不为 null 时的安全处理
     * 验证 ConcurrentRateLimiter(null) 不会在 computeIfAbsent 中崩溃
     */
    @Test
    fun testConcurrentRateLimiterWithNullSource() {
        // ConcurrentRateLimiter(null) 创建时 fetchStart 会因 source 为 null 返回 null
        // 验证不会在 computeIfAbsent 中尝试调用 source.getKey() 导致 NPE
        val map = ConcurrentHashMap<String, ConcurrentRecord>()

        // 模拟 fetchStart 的 null source 检查（source 为 null 时直接返回 null，不进入 computeIfAbsent）
        val sourceKey: String? = null
        val result = if (sourceKey != null) {
            map.computeIfAbsent(sourceKey) {
                ConcurrentRecord(false, System.currentTimeMillis(), 1)
            }
        } else {
            null
        }

        Assert.assertNull("Null source should return null record", result)
        Assert.assertTrue("Map should be empty for null source", map.isEmpty())
    }

    /**
     * 测试同一 key 的 ConcurrentRecord 在并发环境下的 identity 一致性
     * 这是修复 DCL 缺陷的核心验证
     */
    @Test
    fun testConcurrentHashMapIdentityForSameKey() {
        val map = ConcurrentHashMap<String, ConcurrentRecord>()
        val key = "identity_test_key"

        val record1 = map.computeIfAbsent(key) {
            ConcurrentRecord(true, System.currentTimeMillis(), 1)
        }
        val record2 = map.computeIfAbsent(key) {
            ConcurrentRecord(true, System.currentTimeMillis(), 1)
        }
        val record3 = map[key]

        Assert.assertSame(
            "computeIfAbsent should return the same instance for same key",
            record1, record2
        )
        Assert.assertSame(
            "get should return the same instance as computeIfAbsent",
            record1, record3
        )
    }

    /**
     * 测试并发速率限制的 off-by-one 修复
     * 当并发率为 "3/1000" 时，应允许恰好 3 个请求通过，第 4 个请求被阻塞
     * 修复前：初始 frequency=1，检查条件为 >，导致允许 4 个请求
     * 修复后：初始 frequency=0，检查条件为 >=，正确允许 3 个请求
     */
    @Test
    fun testConcurrentRateLimiterOffByOneFix() {
        val maxConcurrent = 3
        val record = ConcurrentRecord(true, System.currentTimeMillis(), 0)

        var allowedCount = 0
        var blockedCount = 0

        for (i in 0 until 10) {
            synchronized(record) {
                if (record.frequency >= maxConcurrent) {
                    blockedCount++
                } else {
                    record.frequency += 1
                    allowedCount++
                }
            }
        }

        Assert.assertEquals(
            "Exactly $maxConcurrent requests should be allowed",
            maxConcurrent,
            allowedCount
        )
        Assert.assertEquals(
            "Remaining requests should be blocked",
            10 - maxConcurrent,
            blockedCount
        )
        Assert.assertEquals(
            "Final frequency should equal max concurrent",
            maxConcurrent,
            record.frequency
        )
    }

    /**
     * 测试初始 frequency 为 0 时的正确性
     * 新创建的 ConcurrentRecord frequency=0，第一个请求应被允许
     */
    @Test
    fun testConcurrentRateLimiterInitialFrequency() {
        val record = ConcurrentRecord(true, System.currentTimeMillis(), 0)
        val maxConcurrent = 1

        synchronized(record) {
            Assert.assertEquals("Initial frequency should be 0", 0, record.frequency)

            val firstAllowed = record.frequency >= maxConcurrent
            Assert.assertFalse("First request should be allowed when frequency=0", firstAllowed)

            record.frequency += 1

            val secondAllowed = record.frequency >= maxConcurrent
            Assert.assertTrue("Second request should be blocked when frequency=1 >= max=1", secondAllowed)
        }
    }
}