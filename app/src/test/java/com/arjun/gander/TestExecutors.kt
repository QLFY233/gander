package com.arjun.gander

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * An executor that runs everything on the calling thread.
 *
 * The app's own work is deliberately off the main thread, which is right and also means a
 * test that wants to assert on the result would otherwise have to wait for a thread it does
 * not control. The home screen's loader has had a private one of these since it was written;
 * the library needs the same, so it is here once.
 */
internal fun directExecutor(): ExecutorService = object : AbstractExecutorService() {
    override fun execute(command: Runnable) = command.run()
    override fun shutdown() = Unit
    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
    override fun isShutdown(): Boolean = false
    override fun isTerminated(): Boolean = false
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
}
