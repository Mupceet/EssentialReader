package io.legado.app.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ProcessStartupUpdateCheckGateTest {

    @Before
    fun resetGate() {
        // 闸是进程级单例，测试间反射归位 consumed，
        // 不往生产代码引入 reset 钩子
        val field = ProcessStartupUpdateCheckGate::class.java.getDeclaredField("consumed")
        field.isAccessible = true
        (field.get(ProcessStartupUpdateCheckGate) as AtomicBoolean).set(false)
    }

    @Test
    fun `enabled check runs only once per process`() {
        assertTrue(ProcessStartupUpdateCheckGate.consume(enabled = true))
        assertFalse(ProcessStartupUpdateCheckGate.consume(enabled = true))
    }

    @Test
    fun `disabled first startup still consumes the process check`() {
        assertFalse(ProcessStartupUpdateCheckGate.consume(enabled = false))
        assertFalse(ProcessStartupUpdateCheckGate.consume(enabled = true))
    }
}
