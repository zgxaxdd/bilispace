package com.naaammme.bbspace.core.designsystem.motion

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations

/**
 * 设备**动效分档**（W3）。三档，与上游 `adaptive/MotionTier` 的 Reduced/Normal/Enhanced
 * 同构，但判据来自本仓能拿到的事实（低内存标记 / 总内存 / 用户动效开关），
 * 并且**把"分档"与"取值"拆开**：分档是纯函数，取值也是纯函数，两者都能 JVM 单测。
 *
 * ## ⚠️ 与 `glass.MotionTier` 的区别（2026-10-18 改名）
 * 本枚举原名也是 `MotionTier`，与 `com.naaammme.bbspace.core.designsystem.glass.MotionTier`
 * （**玻璃预算档** `Reduced / Normal / Enhanced`，语义对齐上游 `adaptive/MotionTier`）
 * 同名不同义：本枚举是**设备能力档**（`Low / Medium / High`，看内存与用户动效开关），
 * 后者是**预算档**（看滚动/转场/降级策略）。接线时混用会静默拿错档位表，故改名为
 * [DeviceMotionTier]；`MotionTierPolicy` / `rememberMotionTier` 的名字保持不变
 * （它们带前缀、不与任何类型名冲突，改名只会扩大 diff）。
 * 反向引用见 `glass/GlassBudget.kt` 的 `MotionTier` KDoc。
 *
 * ## 为什么需要分档（而不是"一律全开"或"一律关"）
 * 用户反馈里有两条同时存在：「软件流畅度不高」与「动效要跟手」。动效不是越多越好：
 * 低端机上 500ms 的弹簧位移会连着丢帧，反而读作"卡"。分档让同一套代码在
 * 低端机**降级为静止/瞬变**、高端机保持完整形变，而不是二选一。
 *
 * ## 为什么不用 `isSystemInDarkTheme` 那类"运行时状态"做判据
 * 设备档在一台机器上是**常量**（内存不会变、低内存标记不会变），只有用户的动效
 * 开关会变。因此测量结果在进程内缓存一次（见 [rememberMotionTier]），开关每次
 * 组合时叠加 —— 避免每张卡都去问一次 ActivityManager。
 */
enum class DeviceMotionTier {
    /** 低端机 / 关闭动效：位移与形变退化为"直接到位"。 */
    Low,

    /** 中端机：保留动效但降速、降幅。 */
    Medium,

    /** 高端机：完整动效。 */
    High
}

/**
 * 分档判据与取值（**纯逻辑**，无 Android 依赖，可 JVM 单测）。
 *
 * 阈值取值的理由：
 * - `LowRamMb = 3072`：3GB 及以下的机器在信息流 + 玻璃链下已经吃紧，动效是最先该让位
 *   的东西（用户要的是"不卡"）；`isLowRamDevice` 系统标记优先于内存读数。
 * - `MediumRamMb = 6144`：4~6GB 是主流中端，保留动效但按 0.6 系数降速；
 *   8GB 以上（High）才吃满动效。
 * - 阈值是**产品判断**，不是实测结论（无设备）；改动它只要改这两个常量。
 */
object MotionTierPolicy {

    /** 低于/等于该内存视为低端（MB）。 */
    const val LowRamMb = 3072

    /** 低于该内存视为中端（MB）；达到或超过为高端。 */
    const val MediumRamMb = 6144

    /**
     * 解析设备档位。
     *
     * @param isLowRamDevice `ActivityManager.isLowRamDevice` 的实测值
     * @param totalRamMb `ActivityManager.MemoryInfo.totalMem` 折算的 MB（拿不到传 0）
     * @param animationEnabled 用户的动效开关（`LocalAnimations.reduceMotion` 取反）
     */
    fun resolve(
        isLowRamDevice: Boolean,
        totalRamMb: Int,
        animationEnabled: Boolean
    ): DeviceMotionTier {
        if (!animationEnabled || isLowRamDevice) return DeviceMotionTier.Low
        if (totalRamMb <= 0) return DeviceMotionTier.Medium // 读不到内存：保守取中档
        return when {
            totalRamMb <= LowRamMb -> DeviceMotionTier.Low
            totalRamMb < MediumRamMb -> DeviceMotionTier.Medium
            else -> DeviceMotionTier.High
        }
    }

    /** 动效强度系数：0（关）/ 0.6（中）/ 1（满）。 */
    fun motionScale(tier: DeviceMotionTier): Float = when (tier) {
        DeviceMotionTier.Low -> 0f
        DeviceMotionTier.Medium -> 0.6f
        DeviceMotionTier.High -> 1f
    }

    /**
     * 玻璃强度上限（本轮不消费，供下一轮液态玻璃接线用）：
     * 低端机不挂玻璃面、中端机封顶 0.6、高端机 1.0。
     *
     * 它是"上限"而不是"取值"：真实强度还要乘 `GlassEffect` 的档位与预算守卫，
     * 这里只负责**不允许**低端机开玻璃。
     */
    fun glassStrengthCap(tier: DeviceMotionTier): Float = when (tier) {
        DeviceMotionTier.Low -> 0f
        DeviceMotionTier.Medium -> 0.6f
        DeviceMotionTier.High -> 1f
    }

    /** 该档是否启用按压/呼吸这类"常驻形变"（Low 档一律静止）。 */
    fun enablesAmbientMotion(tier: DeviceMotionTier): Boolean = tier != DeviceMotionTier.Low

    /**
     * 骨架呼吸周期倍率（中端机放慢到 1.4 倍：同样的明暗变化，更低的重绘频率）。
     * 低端机不消费（[enablesAmbientMotion] 为 false 时骨架走静止分支）。
     */
    fun breathCycleMultiplier(tier: DeviceMotionTier): Float = when (tier) {
        DeviceMotionTier.Low -> 1f
        DeviceMotionTier.Medium -> 1.4f
        DeviceMotionTier.High -> 1f
    }
}

/**
 * 进程内缓存：设备档在一台机器上是常量，只有用户动效开关会变。
 *
 * 为什么不在每个组件里各测一次：`BiliCardSurface` 在信息流里**每张卡都会调**
 * [rememberMotionTier]，而 `ActivityManager.memoryInfo` 会分配一个对象。
 * 缓存后全进程只测一次，组合期只剩一次 `remember` 命中。
 */
private object MotionTierDeviceCache {
    @Volatile
    private var cachedDeviceTier: DeviceMotionTier? = null

    fun tier(context: Context, animationEnabled: Boolean): DeviceMotionTier {
        val device = cachedDeviceTier ?: measure(context).also { cachedDeviceTier = it }
        // 用户开关优先于设备档：关动效 = 低档（与 reduceMotion 的无障碍口径一致）
        return if (animationEnabled) device else DeviceMotionTier.Low
    }

    private fun measure(context: Context): DeviceMotionTier {
        val manager = context.applicationContext
            .getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val totalRamMb = runCatching {
            // ActivityManager 没有 `memoryInfo` 属性：总内存要经 out 参数拿
            // （`getMemoryInfo(MemoryInfo)`），所以这里自己 new 一份再折算成 MB。
            val info = ActivityManager.MemoryInfo()
            manager?.getMemoryInfo(info)
            (info.totalMem / (1024L * 1024L)).toInt()
        }.getOrDefault(0)
        return MotionTierPolicy.resolve(
            isLowRamDevice = manager?.isLowRamDevice == true,
            totalRamMb = totalRamMb,
            animationEnabled = true
        )
    }
}

/**
 * 取当前设备的动效档位（组合内唯一入口）。
 *
 * 只在**需要降级动效**的组件里调用；`LocalAnimations` 的 reduceMotion 变化会重建，
 * 其余情况直接命中 `remember` 与进程缓存（零分配）。
 */
@Composable
fun rememberMotionTier(): DeviceMotionTier {
    val context = LocalContext.current
    val animations = LocalAnimations.current
    return remember(context, animations.reduceMotion) {
        MotionTierDeviceCache.tier(context, animationEnabled = !animations.reduceMotion)
    }
}
