package com.mediahub.player.engine

import javax.inject.Qualifier

/** Media3 引擎工厂限定符（U3-A 双内核 DI）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Media3EngineCreator

/** mpv 引擎工厂限定符（U3-A 双内核 DI）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MpvEngineCreator
