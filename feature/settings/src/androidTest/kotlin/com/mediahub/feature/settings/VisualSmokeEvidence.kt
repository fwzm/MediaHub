package com.mediahub.feature.settings

import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream

/** Opt-in full-screen evidence of the actual Settings route and its shared renderer. */
internal fun captureVisualEvidence(rule: ComposeContentTestRule, name: String) {
    if (InstrumentationRegistry.getArguments().getString("captureVisualEvidence") != "true") return
    require(name.matches(Regex("[a-z-]+")))
    rule.mainClock.advanceTimeBy(500)
    rule.waitForIdle()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
        val directory = File(checkNotNull(instrumentation.context.getExternalFilesDir(null)), "visual-smoke")
        check(directory.isDirectory || directory.mkdirs())
        val destination = File(directory, "$name.png")
        FileOutputStream(destination).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("visualEvidence", destination.absolutePath) })
    } finally {
        bitmap.recycle()
    }
}
