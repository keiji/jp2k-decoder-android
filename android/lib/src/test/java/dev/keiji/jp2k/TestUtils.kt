package dev.keiji.jp2k

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class TestListenableFuture<T>(private val result: T) : ListenableFuture<T> {
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
    override fun isCancelled(): Boolean = false
    override fun isDone(): Boolean = true
    override fun get(): T = result
    override fun get(timeout: Long, unit: TimeUnit?): T = result
    override fun addListener(listener: Runnable, executor: Executor) {
        listener.run()
    }
}

class FailingListenableFuture<T>(private val exception: Throwable) : ListenableFuture<T> {
    override fun cancel(mayInterruptIfRunning: Boolean) = false
    override fun isCancelled() = false
    override fun isDone() = true
    override fun get(): T { throw java.util.concurrent.ExecutionException(exception) }
    override fun get(timeout: Long, unit: TimeUnit?): T { throw java.util.concurrent.ExecutionException(exception) }
    override fun addListener(listener: Runnable, executor: Executor) { listener.run() }
}
