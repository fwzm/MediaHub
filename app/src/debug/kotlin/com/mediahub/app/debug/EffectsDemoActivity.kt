package com.mediahub.app.debug

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.core.ui.effects.FlowGlowPresets
import com.mediahub.core.ui.effects.FlowGlowPreset
import com.mediahub.core.ui.effects.FlowGlowSurface
import com.mediahub.core.ui.effects.SimulatedSpectrumProvider
import com.mediahub.core.ui.effects.SpectrumBars
import com.mediahub.core.ui.effects.SpectrumProvider
import com.mediahub.core.ui.effects.rememberFlowGlowClock

/**
 * Debug-only playground for the FlowGlow engine: replicates the three reference capsules,
 * exposes fps/audio-sim/progress controls, and previews every built-in preset. Launch via:
 * `adb shell am start -n com.mediahub.app/com.mediahub.app.debug.EffectsDemoActivity`
 */
class EffectsDemoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EffectsDemoScreen()
        }
    }
}

@Composable
private fun EffectsDemoScreen() {
    var audioSim by remember { mutableStateOf(true) }
    var fps by remember { mutableIntStateOf(30) }
    var progress by remember { mutableFloatStateOf(0f) }
    var heroIndex by remember { mutableIntStateOf(0) }

    val provider: SpectrumProvider = if (audioSim) SimulatedSpectrumProvider() else SpectrumProvider.Noop
    val clock = rememberFlowGlowClock(fps = fps)

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFE8E8F1))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("FlowGlow engine", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A22))
            Text(
                text = buildString {
                    append(if (Build.VERSION.SDK_INT >= 33) "AGSL active" else "gradient fallback")
                    append(" · ")
                    append(fps)
                    append(" fps · audio ")
                    append(if (audioSim) "sim" else "off")
                },
                fontSize = 12.sp,
                color = Color(0xFF66666F),
            )

            // ---- reference replicas -------------------------------------------------
            DemoCard(FlowGlowPresets.AuroraDark, provider, clock, fps, "OUTER.OR*", "PODCAST EP2")
            DemoCard(FlowGlowPresets.MatchaLight, provider, clock, fps, "MATCHA.TV", "HOW TO SERIES")
            DemoCard(FlowGlowPresets.SandGlow, provider, clock, fps, "SAND.SURF*", "DUNE MIX 2026")

            // ---- hero preset switcher ----------------------------------------------
            val hero = FlowGlowPresets.All[heroIndex]
            FlowGlowSurface(
                palette = hero.palette,
                config = hero.config.copy(fps = fps),
                spectrum = provider,
                progressProvider = { progress },
                clock = clock,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(horizontal = 22.dp),
                ) {
                    Text(hero.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = textColor(hero))
                    Text(
                        "HERO · position ${(progress * 100).toInt()}%",
                        fontSize = 11.sp,
                        color = textColor(hero).copy(alpha = 0.7f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowGlowPresets.All.forEachIndexed { index, preset ->
                    if (index == heroIndex) {
                        Button(onClick = { heroIndex = index }) { Text(preset.name, fontSize = 11.sp) }
                    } else {
                        OutlinedButton(onClick = { heroIndex = index }) { Text(preset.name, fontSize = 11.sp) }
                    }
                }
            }

            // ---- controls -----------------------------------------------------------
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (audioSim) {
                    Button(onClick = { audioSim = false }) { Text("AUDIO SIM: ON", fontSize = 11.sp) }
                } else {
                    OutlinedButton(onClick = { audioSim = true }) { Text("AUDIO SIM: OFF", fontSize = 11.sp) }
                }
                listOf(15, 30, 60).forEach { candidate ->
                    if (fps == candidate) {
                        Button(onClick = { fps = candidate }) { Text("$candidate", fontSize = 11.sp) }
                    } else {
                        OutlinedButton(onClick = { fps = candidate }) { Text("$candidate", fontSize = 11.sp) }
                    }
                }
            }
            Text("Playback position ${(progress * 100).toInt()}%", fontSize = 12.sp, color = Color(0xFF44444D))
            Slider(value = progress, onValueChange = { progress = it })

            Text("Spectrum (bass / mid / treble, EMA smoothed)", fontSize = 12.sp, color = Color(0xFF44444D))
            SpectrumBars(
                clock = clock,
                provider = provider,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp),
                color = Color(0xFF33333D),
            )
        }
    }
}

@Composable
private fun DemoCard(
    preset: FlowGlowPreset,
    provider: SpectrumProvider,
    clock: com.mediahub.core.ui.effects.FlowGlowClock,
    fps: Int,
    title: String,
    subtitle: String,
) {
    FlowGlowSurface(
        palette = preset.palette,
        config = preset.config.copy(fps = fps),
        spectrum = provider,
        clock = clock,
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = textColor(preset))
                Text(subtitle, fontSize = 11.sp, color = textColor(preset).copy(alpha = 0.65f))
            }
            SpectrumBars(
                clock = clock,
                provider = provider,
                modifier = Modifier
                    .height(22.dp)
                    .padding(start = 12.dp)
                    .fillMaxWidth(0.12f),
                color = textColor(preset),
            )
        }
    }
}

private fun textColor(preset: FlowGlowPreset): Color =
    if (Color(preset.palette.background).luminance() > 0.5f) Color(0xFF1C1C24) else Color(0xFFF4F4F8)
