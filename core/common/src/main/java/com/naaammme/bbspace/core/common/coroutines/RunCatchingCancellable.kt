package com.naaammme.bbspace.core.common.coroutines

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` 的**可取消**版本：`CancellationException` 原样重抛，其余异常照常包成
 * [Result.failure]。
 *
 * 为什么需要它：Kotlin 标准库的 `runCatching` 捕获的是 `Throwable`，会把
 * `CancellationException` 一起吞掉并转成 `Result.failure` —— 这**破坏结构化并发**：
 * 协程不再从挂起点向上展开，调用方的 `onFailure {}` 分支照常执行，于是"页面已销毁
 * 却还在改状态"，而且**取消与真实失败在调用方无法区分**。
 *
 * 项目里已经撞过这个坑并留了补丁（`LoginViewModel.kt:86-88` 的注释原文：
 * "协程被取消（退出页面/重启轮询）时 Repo 的 runCatching 会把 CancellationException
 * 转成 failure"），但只有那一处做了特判，其余调用方仍在踩。与其在每处补特判，
 * 不如把语义收口到这里：**包可取消的挂起调用一律用它**。
 *
 * 用法（与 `runCatching` 完全一致，可直接替换）：
 * ```
 * suspend fun load(): Result<Data> = runCatchingCancellable {
 *     restClient.getSigned(...)   // suspend，可取消
 * }
 * ```
 *
 * **可以直接在 suspend 函数里调用**：`block` 是 `inline` 的非 crossinline 参数，
 * 编译器会把它内联进调用处，因此在块内调用挂起函数是合法的。不需要额外的
 * "挂起版"重载。
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (c: CancellationException) {
        // 取消必须向上传播，不能变成 failure
        throw c
    } catch (t: Throwable) {
        Result.failure(t)
    }
