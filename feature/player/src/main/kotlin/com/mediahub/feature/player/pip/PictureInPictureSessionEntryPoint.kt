package com.mediahub.feature.player.pip

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 播放页通过该入口拿到进程内唯一的 [PictureInPictureSession]。
 *
 * 播放页在 feature 层，MainActivity 在 app 层；两边都只依赖这个会话，不互相引用。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PictureInPictureSessionEntryPoint {
    fun pictureInPictureSession(): PictureInPictureSession
}
