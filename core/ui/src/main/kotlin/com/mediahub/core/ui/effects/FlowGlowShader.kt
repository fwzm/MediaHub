package com.mediahub.core.ui.effects

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi

/**
 * AGSL implementation of the flow-glow field.
 *
 * Pipeline per pixel: aspect-corrected uv -> domain-warped fbm -> two drifting bass-reactive
 * glow hotspots -> palette mix -> iridescent edge band -> white-hot bloom -> premultiplied
 * output. RuntimeShader is available from API 33; [FlowGlowSurface] falls back to a static
 * gradient below that.
 */
@RequiresApi(33)
internal object FlowGlowShader {

    val SOURCE: String = """
        uniform float2 uResolution;
        uniform float uTime;
        uniform float uProgress;
        uniform float4 uBackground;
        uniform float4 uPrimary;
        uniform float4 uSecondary;
        uniform float4 uAccent;
        uniform float uBass;
        uniform float uMid;
        uniform float uTreble;
        uniform float uAmplitude;
        uniform float uSpeed;
        uniform float uWarp;
        uniform float uIridescence;
        uniform float uBloom;
        uniform float uNoiseScale;
        uniform float uOpacity;

        const float TAU = 6.2831853;

        float hash21(float2 p) {
            float3 p3 = fract(float3(p.x, p.y, p.x) * 0.1031);
            p3 += dot(p3, float3(p3.y, p3.z, p3.x) + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        float vnoise(float2 p) {
            float2 i = floor(p);
            float2 f = fract(p);
            float2 u = f * f * (3.0 - 2.0 * f);
            float a = hash21(i);
            float b = hash21(i + float2(1.0, 0.0));
            float c = hash21(i + float2(0.0, 1.0));
            float d = hash21(i + float2(1.0, 1.0));
            return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
        }

        float fbm(float2 p) {
            float v = 0.0;
            float amp = 0.55;
            for (int i = 0; i < 4; i++) {
                v += amp * vnoise(p);
                p = p * 2.02 + float2(17.3, 9.1);
                amp *= 0.5;
            }
            return v;
        }

        half4 main(float2 fragCoord) {
            float2 res = max(uResolution, float2(1.0, 1.0));
            float2 uv = fragCoord / res;
            float aspect = res.x / res.y;
            float2 p0 = float2(uv.x * aspect, uv.y);
            float2 p = p0 * uNoiseScale;

            float t = uTime * 0.10 * uSpeed + uProgress * 3.0;
            float warp = uWarp * (1.0 + uMid * 0.7);
            float2 flow = float2(t * 0.40, sin(t * 0.30) * 0.10);

            float2 q = float2(fbm(p * 0.9 + flow), fbm(p * 0.9 + flow + float2(5.2, 1.3)));
            float2 r = float2(
                fbm(p * 0.9 + warp * q + float2(1.7, 9.2) + flow * 0.6),
                fbm(p * 0.9 + warp * q + float2(8.3, 2.8) - flow * 0.4)
            );
            float f = fbm(p * 0.8 + warp * r * 1.3);

            // soft elongated ridge hotspot, swollen by bass energy
            float reach = 0.085 * (1.0 + uBass * 1.5);
            float2 c1 = float2(aspect * (0.58 + 0.24 * sin(t * 0.7)), 0.48 + 0.24 * sin(t * 0.49 + 1.7));
            float2 c2 = float2(aspect * (0.32 + 0.18 * sin(t * 0.53 + 2.4)), 0.58 + 0.20 * sin(t * 0.77 + 4.1));
            float2 d1 = p0 - c1;
            d1.x *= 0.55;
            float glow = exp(-dot(d1, d1) / reach) * 1.15
                       + exp(-dot(p0 - c2, p0 - c2) / (reach * 1.9)) * 0.60;
            glow *= (0.60 + uBass * 1.1) * (0.72 + 0.55 * f);

            // tonal structure: background floor -> secondary -> primary. The brightness
            // floor scales with background luminance so dark palettes stay near-black
            // while light palettes stay airy.
            float clouds = f * 0.75;
            float bgLum = dot(uBackground.rgb, float3(0.2126, 0.7152, 0.0722));
            float floorK = mix(0.30, 0.78, smoothstep(0.1, 0.75, bgLum));
            float3 tone = mix(uSecondary.rgb, uPrimary.rgb, smoothstep(0.35, 1.05, clouds));
            float3 base = mix(uBackground.rgb, tone, smoothstep(0.02, 0.62, clouds));
            base *= floorK + (0.85 - floorK) * smoothstep(0.0, 0.9, clouds);

            // subtle accent pooling in the dim clouds
            base = mix(base, base * (uAccent.rgb * 1.3), (1.0 - smoothstep(0.0, 0.6, clouds)) * 0.26);

            // white-hot core with spectral dispersion: three masks with staggered
            // thresholds (blue turns on furthest out, then green, then red), so the fringe
            // reads blue -> green -> yellow -> white like light dispersion. The core body
            // is white only when uBloom is high; low-bloom light palettes get a soft
            // primary-tinted watercolor highlight instead of a white spotlight.
            float trebleEnergy = clamp(uTreble, 0.0, 1.0);
            float dispersionOffset = 0.08 + trebleEnergy * 0.04;
            float coreMid = smoothstep(0.28, 0.95, glow);
            float3 core = float3(
                smoothstep(0.28, 0.95, glow - dispersionOffset),
                coreMid,
                smoothstep(0.28, 0.95, glow + dispersionOffset)
            );
            float3 dispersion = core - float3(coreMid);
            float coreWhiteness = clamp(uBloom * 1.1, 0.0, 1.0);
            float3 coreColor = mix(uPrimary.rgb * 1.25, float3(1.0), coreWhiteness);
            float dispersionGain = 0.85 + trebleEnergy * 0.30;
            float3 col = base + coreMid * uBloom * coreColor + dispersion * uBloom * dispersionGain;

            // faint iridescent shimmer just outside the core edge (dark palettes only)
            float edgeBand = smoothstep(0.10, 0.28, glow) * (1.0 - smoothstep(0.28, 0.55, glow));
            float3 irid = 0.5 + 0.5 * cos(TAU * (glow * 1.1 + float3(0.0, 0.33, 0.67)));
            col += irid * edgeBand * uIridescence * (0.18 + trebleEnergy * 0.08);

            // Amplitude is deliberately independent from band balance. Keep the global
            // response restrained so loud passages breathe without flashing or clipping.
            float amplitudeGain = mix(0.92, 1.08, clamp(uAmplitude, 0.0, 1.0));
            col *= amplitudeGain;

            float a = clamp(uOpacity, 0.0, 1.0);
            return half4(col * a, a);
        }
    """.trimIndent()

    fun apply(
        shader: RuntimeShader,
        width: Float,
        height: Float,
        timeSec: Float,
        progress: Float,
        palette: VisualPalette,
        config: VisualEffectConfig,
        spectrum: SpectrumFrame,
    ) {
        shader.setFloatUniform("uResolution", width, height)
        shader.setFloatUniform("uTime", timeSec)
        shader.setFloatUniform("uProgress", progress.coerceIn(0f, 1f))
        shader.setFloatUniform("uBackground", colorRgba(palette.background))
        shader.setFloatUniform("uPrimary", colorRgba(palette.primary))
        shader.setFloatUniform("uSecondary", colorRgba(palette.secondary))
        shader.setFloatUniform("uAccent", colorRgba(palette.accent))
        shader.setFloatUniform("uBass", spectrum.bass)
        shader.setFloatUniform("uMid", spectrum.mid)
        shader.setFloatUniform("uTreble", spectrum.treble)
        shader.setFloatUniform("uAmplitude", spectrum.amplitude)
        shader.setFloatUniform("uSpeed", config.speed)
        shader.setFloatUniform("uWarp", config.warp)
        shader.setFloatUniform("uIridescence", config.iridescence)
        shader.setFloatUniform("uBloom", config.bloom)
        shader.setFloatUniform("uNoiseScale", config.noiseScale)
        shader.setFloatUniform("uOpacity", config.opacity)
    }

    private fun colorRgba(color: Int): FloatArray = floatArrayOf(
        ((color shr 16) and 0xFF) / 255f,
        ((color shr 8) and 0xFF) / 255f,
        (color and 0xFF) / 255f,
        ((color ushr 24) and 0xFF) / 255f,
    )
}
