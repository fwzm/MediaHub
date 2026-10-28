package com.mediahub.app.di

import com.mediahub.core.network.NetworkExchangeLedger
import com.mediahub.player.engine.PlayerFactory
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Field

/**
 * G4 · M16 观测台账的**生产接线**契约（先红后绿）。
 *
 * 背景（V12 §05.1）：ledger 参数必须真正接到生产构造点。
 * 「存在一个可选参数、默认 null」**不算接线** —— 这正是上一轮留下的缺口：
 *   - `PlayerFactory` 有 `ledger: NetworkExchangeLedger? = null`，
 *     但 `AppModule.providePlayerFactory` 构造时**没有传**；
 *   - mpv 侧 `MpvPlaybackEngine` 次构造器调用 `createMpvBridge(httpClientFactory)` 时**没有传** ledger。
 *
 * 本测试用反射直接检查生产装配产物的字段，避免「只在测试里传 ledger 就宣称接线完成」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObservabilityProductionWiringTest {

    private fun ledgerFieldOf(instance: Any): NetworkExchangeLedger? {
        val field: Field? = instance.javaClass.declaredFields.firstOrNull { it.type == NetworkExchangeLedger::class.java }
        assertNotNull(
            "找不到 ledger 字段：" + instance.javaClass.name +
                " fields=" + instance.javaClass.declaredFields.joinToString { it.name + ":" + it.type.simpleName },
            field,
        )
        field!!.isAccessible = true
        return field.get(instance) as NetworkExchangeLedger?
    }

    @Test
    fun `production PlayerFactory is constructed with a real ledger`() {
        val context = RuntimeEnvironment.getApplication()
        val ledger = NetworkExchangeLedger()
        val factory = PlayerFactory(
            context,
            com.mediahub.player.engine.MediaCacheProvider(context),
            com.mediahub.core.network.UserAgentPolicy(),
            com.mediahub.core.network.ServerIdResolver { null },
            ledger,
        )
        val wired = ledgerFieldOf(factory)
        assertNotNull("PlayerFactory 必须真的持有 ledger（否则 Media3 不可观测通道永不登记）", wired)
        assertTrue("注入的必须是同一个实例", wired === ledger)
    }

    @Test
    fun `AppModule provides PlayerFactory already wired to the injected ledger`() {
        // 端到端：走**生产 provide 函数**本身（而不是在测试里手工传 ledger），
        // 证明 DI 图装配出来的 PlayerFactory 真的带着台账。
        val context = RuntimeEnvironment.getApplication()
        val ledger = NetworkExchangeLedger()
        val factory = com.mediahub.app.di.AppModule.providePlayerFactory(
            context,
            com.mediahub.player.engine.MediaCacheProvider(context),
            com.mediahub.core.network.UserAgentPolicy(),
            com.mediahub.core.network.ServerIdResolver { null },
            ledger,
        )
        assertTrue(
            "AppModule.providePlayerFactory 必须把 ledger 传给 PlayerFactory（否则 Media3 路径永不登记不可观测）",
            ledgerFieldOf(factory) === ledger,
        )
    }

    @Test
    fun `mpv engine creator accepts a ledger so the bridge can observe`() {
        val ctor = com.mediahub.player.mpv.MpvPlaybackEngineCreator::class.java.constructors
            .firstOrNull { c -> c.parameterTypes.any { it == NetworkExchangeLedger::class.java } }
        assertNotNull(
            "MpvPlaybackEngineCreator 必须接受 ledger 参数，否则生产 mpv 路径永远观测不到回环/本地读取" +
                "（现有构造参数：" +
                com.mediahub.player.mpv.MpvPlaybackEngineCreator::class.java.constructors.first()
                    .parameterTypes.joinToString { it.simpleName } + "）",
            ctor,
        )
    }
}
