package io.legado.app.eink.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow

/**
 * 并发消费流的每个元素（语义与宿主 utils.FlowExtensions.onEachParallel
 * 一致）：flatMapMerge 限并发 + buffer(0) 不预取，下游 collect 时按
 * 到达顺序汇合。
 */
@OptIn(ExperimentalCoroutinesApi::class)
inline fun <T> Flow<T>.onEachParallel(
    concurrency: Int,
    crossinline action: suspend (T) -> Unit
): Flow<T> = flatMapMerge(concurrency) { value ->
    flow {
        action(value)
        emit(value)
    }
}.buffer(0)
