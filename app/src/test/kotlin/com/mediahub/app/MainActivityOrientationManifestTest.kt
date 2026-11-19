package com.mediahub.app

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 审计 #97：MainActivity 不得被清单锁定为竖屏，否则平板横屏浏览无法生效。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityOrientationManifestTest {

    @Test
    fun `main activity is not locked to portrait`() {
        val context = RuntimeEnvironment.getApplication()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_ACTIVITIES,
        )
        val activity = packageInfo.activities?.firstOrNull {
            it.name == MainActivity::class.java.name || it.name == ".MainActivity"
        }
        assertNotNull(
            "Robolectric 必须能读到 MainActivity 的 ActivityInfo；实际=" +
                packageInfo.activities?.joinToString { it.name }.orEmpty(),
            activity,
        )
        assertNotEquals(
            "MainActivity 不得在 AndroidManifest 中声明 screenOrientation=portrait",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            activity!!.screenOrientation,
        )
    }
}
