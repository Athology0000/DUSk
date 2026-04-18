package org.cobalt.internal.spotify

import kotlin.math.*
import net.minecraft.client.Minecraft
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.MouseEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.NvgEvent
import org.cobalt.api.hud.HudAnchor
import org.cobalt.api.hud.hudElement
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.ModeSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.api.util.ui.helper.Gradient
import org.cobalt.api.util.ui.helper.Image

object SpotifyModule : Module("Spotify") {

    private val mc = Minecraft.getInstance()

    // -- Settings --------------------------------------------------------------

    private val color1Setting = TextSetting(
        "Color 1", "Gradient start color (hex, no #).", "1DB954"
    )
    private val color2Setting = TextSetting(
        "Color 2", "Gradient end color (hex, no #).", "9B59B6"
    )
    private val gradientDirectionSetting = ModeSetting(
        "Gradient", "Progress bar gradient direction.",
        0, arrayOf("Left->Right", "Top->Bottom")
    )
    private val autoColorSetting  = CheckboxSetting("Auto Color",  "Derive gradient color from album art.", true)
    private val glowSetting       = CheckboxSetting("Glow",        "Animated glow border.",                 true)
    private val particlesSetting  = CheckboxSetting("Particles",   "Sparkle particle effects.",             true)
    private val showTimeSetting   = CheckboxSetting("Show Time",   "Show elapsed / total time.",            true)
    private val waveformSetting   = CheckboxSetting("Waveform",    "Show a Spotify audio-reactive waveform.", true)

    // -- Derived colors --------------------------------------------------------

    private fun hexToArgb(hex: String, alpha: Int = 0xFF): Int {
        val stripped = hex.trim().trimStart('#').takeLast(6).padStart(6, '0')
        val rgb = stripped.toIntOrNull(16) ?: 0x1DB954
        return (alpha shl 24) or (rgb and 0xFFFFFF)
    }

    private val manualColor1 get() = hexToArgb(color1Setting.value)
    private val manualColor2 get() = hexToArgb(color2Setting.value)

    private var artDominantC1: Int = 0xFF1DB954.toInt()
    private var artDominantC2: Int = 0xFF9B59B6.toInt()
    private var dominantArtVersion = -1

    private val color1 get() = if (autoColorSetting.value) artDominantC1 else manualColor1
    private val color2 get() = if (autoColorSetting.value) artDominantC2 else manualColor2

    // -- Art cache + crossfade -------------------------------------------------

    private var cachedArtVersion  = -1
    private var cachedArtImage:   Image? = null
    private var prevArtImage:     Image? = null
    private var artFadeStartMs:   Long  = 0L
    private const val ART_FADE_MS = 700L

    private fun artFadeAlpha(now: Long): Float =
        ((now - artFadeStartMs).toFloat() / ART_FADE_MS).coerceIn(0f, 1f)

    // -- SVG control icons -----------------------------------------------------

    private var iconsLoaded = false
    private var imgPrev:  Image? = null
    private var imgPlay:  Image? = null
    private var imgPause: Image? = null
    private var imgNext:  Image? = null

    private fun ensureIconsLoaded() {
        if (iconsLoaded) return
        iconsLoaded = true
        imgPrev  = runCatching { NVGRenderer.createImage("/assets/cobalt/textures/ui/ic_spotify_prev.svg")  }.getOrNull()
        imgPlay  = runCatching { NVGRenderer.createImage("/assets/cobalt/textures/ui/ic_spotify_play.svg")  }.getOrNull()
        imgPause = runCatching { NVGRenderer.createImage("/assets/cobalt/textures/ui/ic_spotify_pause.svg") }.getOrNull()
        imgNext  = runCatching { NVGRenderer.createImage("/assets/cobalt/textures/ui/ic_spotify_next.svg")  }.getOrNull()
    }

    // -- Scroll + particle state ------------------------------------------------

    private var lastTrackName    = ""
    private var lastRenderMs     = System.currentTimeMillis()
    private var titleScrollX     = 0f
    private var titleScrollDir   = 1f
    private var titleScrollPause = 0f
    private val waveformHistory  = FloatArray(WAVE_BARS)
    private var waveformSampleTimer = 0f
    private var waveformEnvelope = 0f
    private var waveformPeak = 0f
    private var waveformBeatPulse = 0f
    private var waveformMotionTime = 0f
    private var waveformAdaptivePeak = 0.08f

    // -- Layout constants ------------------------------------------------------
    //
    // Normal HUD (no controls):   title y+18, artist y+31, time y+44, bar y+58
    // With controls (screen open): same rows, then buttons y+BTN_Y_L, bar y+BAR_Y_CTRL

    private const val W              = 305f
    private const val H              = 90f
    private const val ART            = 60f
    private const val ART_FRAME      = ART      // ART_PAD = 0
    private const val CORNER         = 10f
    private const val PAD            = 8f
    private const val TEXT_X         = PAD + ART_FRAME + 8f  // 76f
    private const val BTN_W          = 32f
    private const val BTN_H          = 18f
    private const val BTN_Y_L        = 54f      // local Y of control buttons (used in onMouseClick)
    private const val BAR_Y          = 76f      // bar always at same Y; art ends at y+68, 8px gap
    private const val WAVE_W         = 82f
    private const val WAVE_H         = 26f
    private const val WAVE_Y         = 16f
    private const val WAVE_GAP       = 8f
    private const val WAVE_BARS      = 18
    private const val WAVE_SAMPLE_S  = 0.045f
    private const val WAVE_NOISE_FLOOR = 0.00005f
    private const val WAVE_RENDER_SAMPLES = 38
    private const val WAVE_BEAT_TRIGGER = 0.12f

    // -- HUD -------------------------------------------------------------------

    val spotifyHud = hudElement("spotify-now-playing", "Spotify Now Playing", "Spotify currently playing HUD") {
        anchor  = HudAnchor.BOTTOM_LEFT
        offsetX = 10f
        offsetY = 10f

        width  { W }
        height { H }

        render { x, y, scale ->
            // HudModuleManager only calls this when mc.screen == null, so controls are hidden.
            // No mouse-to-screen conversion is needed on this path.
            renderHudContent(x, y, 0f, 0f, scale, showControls = false)
        }
    }

    // -- NvgEvent - render full HUD (with controls) when any screen is open ----

    @SubscribeEvent
    fun onNvg(event: NvgEvent) {
        if (mc.screen == null) return
        if (mc.level == null) return
        if (!spotifyHud.enabled) return

        val window  = mc.window
        val sw      = window.screenWidth.toFloat()
        val sh      = window.screenHeight.toFloat()
        val (sx, sy) = spotifyHud.getScreenPosition(sw, sh)
        val s       = spotifyHud.scale

        NVGRenderer.beginFrame(sw, sh)
        NVGRenderer.push()
        NVGRenderer.translate(sx, sy)
        NVGRenderer.scale(s, s)
        renderHudContent(0f, 0f, sx, sy, s, showControls = true)
        NVGRenderer.pop()
        NVGRenderer.endFrame()
    }

    // -- MouseEvent - control button clicks when any screen is open ------------

    @SubscribeEvent
    fun onMouseClick(event: MouseEvent.LeftClick) {
        if (mc.screen == null) return
        if (mc.level == null) return
        if (!spotifyHud.enabled) return
        SpotifyPoller.current ?: return

        val window  = mc.window
        val sw      = window.screenWidth.toFloat()
        val sh      = window.screenHeight.toFloat()
        val (sx, sy) = spotifyHud.getScreenPosition(sw, sh)
        val s       = spotifyHud.scale

        val mx = mc.mouseHandler.xpos().toFloat()
        val my = mc.mouseHandler.ypos().toFloat()
        val lmx = (mx - sx) / s
        val lmy = (my - sy) / s

        val totalBtnW = BTN_W * 3f + 8f * 2f
        val textAreaW = W - TEXT_X - PAD
        val btnStartX = TEXT_X + (textAreaW - totalBtnW) / 2f
        val b0x = btnStartX
        val b1x = btnStartX + BTN_W + 8f
        val b2x = btnStartX + (BTN_W + 8f) * 2f

        fun hitsBtn(bx: Float): Boolean =
            lmx >= bx && lmx <= bx + BTN_W && lmy >= BTN_Y_L && lmy <= BTN_Y_L + BTN_H

        when {
            hitsBtn(b0x) -> { SpotifyPoller.sendCommand(0xB1); event.setCancelled(true) } // Prev
            hitsBtn(b1x) -> { SpotifyPoller.sendCommand(0xB3); event.setCancelled(true) } // Play/Pause
            hitsBtn(b2x) -> { SpotifyPoller.sendCommand(0xB0); event.setCancelled(true) } // Next
        }
    }

    // -- Init ------------------------------------------------------------------

    init {
        addSetting(
            color1Setting, color2Setting,
            gradientDirectionSetting, autoColorSetting,
            glowSetting, particlesSetting, showTimeSetting, waveformSetting,
        )
        EventBus.register(this)
    }

    @SubscribeEvent
    fun onTick(event: TickEvent.End) {
        if (!spotifyHud.enabled) return
        SpotifyPoller.update()
    }

    // -- Core HUD render -------------------------------------------------------

    private fun renderHudContent(
        x: Float, y: Float,
        screenX: Float, screenY: Float, scale: Float,
        showControls: Boolean,
    ) {
        val now  = System.currentTimeMillis()
        val dt   = ((now - lastRenderMs) / 1000f).coerceIn(0f, 0.1f)
        lastRenderMs = now
        waveformMotionTime = (waveformMotionTime + dt).let { if (it >= 10_000f) it - 10_000f else it }

        val c1    = color1
        val c2    = color2
        val track = SpotifyPoller.current

        refreshArtCache(now)
        drawBackground(x, y, W, H, now, c1, c2)
        drawAlbumArt(x, y, track, now)

        val textColor = 0xFFFFFFFF.toInt()
        val dimColor  = 0xBBA8B8D0.toInt()

        if (track == null) {
            NVGRenderer.text("Open Spotify and play something",
                x + TEXT_X, y + H / 2f - 5f, 10f, dimColor)
            updateWaveformState(dt, false, 0f)
            return
        }

        updateWaveformState(dt, track.isPlaying, SpotifyPoller.audioLevel)

        // Reset scroll + burst particles on track change
        if (track.name != lastTrackName) {
            lastTrackName    = track.name
            titleScrollX     = 0f
            titleScrollDir   = 1f
            titleScrollPause = 1.5f
            waveformHistory.fill(0f)
            waveformSampleTimer = 0f
            waveformEnvelope = 0f
            waveformPeak = 0f
            waveformBeatPulse = 0f
            waveformMotionTime = 0f
            waveformAdaptivePeak = 0.08f
            if (particlesSetting.value) {
                SpotifyParticles.burst(x + PAD, y + BAR_Y, W - PAD * 2f, c1, c2)
            }
        }

        val waveformReservedW = if (waveformSetting.value) WAVE_W + WAVE_GAP else 0f
        val textMaxW = (W - TEXT_X - PAD - waveformReservedW).coerceAtLeast(60f)

        // Title row - full width of text area (no time here)
        drawScrollingTitle(track.name, x + TEXT_X, y + 18f, textMaxW, 12f, textColor, dt)

        // Artist row
        NVGRenderer.text(truncate(track.artist, textMaxW, 10f), x + TEXT_X, y + 33f, 10f, dimColor)

        // Time row - below artist
        if (showTimeSetting.value) {
            val timeStr = "${formatMs(track.currentProgressMs)} / ${formatMs(track.durationMs)}"
            NVGRenderer.text(timeStr, x + TEXT_X, y + 46f, 9f, dimColor)
        }

        if (waveformSetting.value) {
            drawWaveform(x + W - PAD - WAVE_W, y + WAVE_Y, WAVE_W, WAVE_H, c1, c2, track.isPlaying)
        }

        // Control buttons - only when a screen is open
        if (showControls) {
            ensureIconsLoaded()

            val rawMx = mc.mouseHandler.xpos().toFloat()
            val rawMy = mc.mouseHandler.ypos().toFloat()
            val lmx   = if (scale > 0f) (rawMx - screenX) / scale else rawMx
            val lmy   = if (scale > 0f) (rawMy - screenY) / scale else rawMy

            val totalBtnW  = BTN_W * 3f + 8f * 2f
            val textAreaW  = W - TEXT_X - PAD          // width of text column (right of art)
            val btnStartX  = x + TEXT_X + (textAreaW - totalBtnW) / 2f
            val b0x = btnStartX
            val b1x = btnStartX + BTN_W + 8f
            val b2x = btnStartX + (BTN_W + 8f) * 2f
            val bY  = y + BTN_Y_L

            val playImg = if (track.isPlaying) imgPause else imgPlay
            listOf(b0x to imgPrev, b1x to playImg, b2x to imgNext).forEach { (bx, img) ->
                val localBx = bx - x
                val hovered = lmx >= localBx && lmx <= localBx + BTN_W &&
                              lmy >= BTN_Y_L  && lmy <= BTN_Y_L + BTN_H
                val bgAlpha = if (hovered) 0x55 else 0x22
                NVGRenderer.rect(bx, bY, BTN_W, BTN_H, (bgAlpha shl 24) or 0xFFFFFF, 6f)
                NVGRenderer.hollowRect(bx, bY, BTN_W, BTN_H, 1f, 0x33FFFFFF, 6f)
                if (img != null) {
                    val iconSize = BTN_H - 6f
                    NVGRenderer.image(
                        img,
                        bx + (BTN_W - iconSize) / 2f,
                        bY + (BTN_H - iconSize) / 2f,
                        iconSize, iconSize, 0f,
                        if (hovered) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt()
                    )
                }
            }
        }

        // Progress bar
        drawProgressBar(x + PAD, y + BAR_Y, W - PAD * 2f, 6f, track, c1, c2)

        // Particles
        if (particlesSetting.value) {
            val fillW = ((W - PAD * 2f) * (track.currentProgressMs.toFloat() / track.durationMs)).coerceIn(0f, W - PAD * 2f)
            SpotifyParticles.update(dt, x + PAD, y + BAR_Y, fillW, c1, c2, track.isPlaying)
            SpotifyParticles.render()
        }
    }

    // -- Shared drawing helpers -------------------------------------------------

    private fun drawBackground(x: Float, y: Float, w: Float, h: Float, now: Long, c1: Int, c2: Int) {
        val twoPi = (Math.PI * 2).toFloat()

        if (glowSetting.value) {
            val pulse = 0.4f + 0.6f * cos((now % 4000L).toFloat() / 4000f * twoPi)
            val a2 = (0x18 * pulse).toInt().coerceIn(0, 0x28)
            val a1 = (0x2A * pulse).toInt().coerceIn(0, 0x40)
            NVGRenderer.hollowRect(x - 3f, y - 3f, w + 6f, h + 6f, 2.5f,
                (a2 shl 24) or (c1 and 0x00FFFFFF), CORNER + 3f)
            NVGRenderer.hollowRect(x - 1.5f, y - 1.5f, w + 3f, h + 3f, 1.5f,
                (a1 shl 24) or (c1 and 0x00FFFFFF), CORNER + 1.5f)
        }

        NVGRenderer.rect(x, y, w, h, 0xFF0A0E1A.toInt(), CORNER)
        NVGRenderer.gradientRect(x, y, w, h * 0.5f, 0x14FFFFFF, 0x00000000, Gradient.TopToBottom, CORNER)

        val angle  = (now % 10000L).toFloat() / 10000f * twoPi
        val shiftX = cos(angle) * (w * 0.42f)
        NVGRenderer.hollowGradientRectShifted(x, y, w, h, 1.5f, c1, c2, Gradient.LeftToRight, CORNER, shiftX, 0f)
    }

    /** Art positioned flush to top-left (PAD from top/left edge). Art ends at y+PAD+ART = y+68. */
    private fun drawAlbumArt(x: Float, y: Float, track: SpotifyTrack?, now: Long) {
        val artX = x + PAD
        val artY = y + PAD

        NVGRenderer.rect(artX, artY, ART_FRAME, ART_FRAME, 0xFF101521.toInt(), 8f)
        NVGRenderer.hollowRect(artX, artY, ART_FRAME, ART_FRAME, 1f, 0x22FFFFFF, 8f)

        val fade = artFadeAlpha(now)
        val prev = prevArtImage
        val curr = cachedArtImage

        if (prev != null && fade < 1f) {
            val a = ((1f - fade) * 0xFF).toInt().coerceIn(0, 0xFF)
            NVGRenderer.image(prev, artX, artY, ART, ART, 6f, (a shl 24) or 0xFFFFFF)
        }
        if (curr != null) {
            val a = (fade * 0xFF).toInt().coerceIn(0, 0xFF)
            NVGRenderer.image(curr, artX, artY, ART, ART, 6f, (a shl 24) or 0xFFFFFF)
            if (track?.isPlaying == false) {
                NVGRenderer.rect(artX, artY, ART, ART, 0x55000000, 6f)
            }
        } else {
            NVGRenderer.rect(artX, artY, ART, ART, 0xFF141824.toInt(), 6f)
            val noteW = NVGRenderer.textWidth("\u266A", 22f)
            NVGRenderer.text("\u266A", artX + ART / 2f - noteW / 2f, artY + ART / 2f - 11f, 22f, 0x33FFFFFF)
        }

        if (fade >= 1f && prev != null) {
            NVGRenderer.deleteImage(prev)
            prevArtImage = null
        }
    }

    private fun drawProgressBar(
        barX: Float, barY: Float, barW: Float, barH: Float,
        track: SpotifyTrack, c1: Int, c2: Int,
    ) {
        val barR  = barH / 2f
        val prog  = (track.currentProgressMs.toFloat() / track.durationMs).coerceIn(0f, 1f)
        val fillW = (barW * prog).coerceAtLeast(0f)

        NVGRenderer.rect(barX, barY, barW, barH, 0xFF1A2040.toInt(), barR)

        if (fillW > barR * 2f) {
            val gradient = if (gradientDirectionSetting.value == 1) Gradient.TopToBottom else Gradient.LeftToRight
            NVGRenderer.pushScissor(barX, barY, fillW, barH)
            NVGRenderer.gradientRect(barX, barY, barW, barH, c1, c2, gradient, barR)
            NVGRenderer.popScissor()
            val tipX = barX + fillW
            NVGRenderer.circle(tipX, barY + barH / 2f, barH * 0.7f, (0x88 shl 24) or (c1 and 0x00FFFFFF))
            NVGRenderer.circle(tipX, barY + barH / 2f, barH * 0.38f, 0xCCFFFFFF.toInt())
        }
    }

    private fun updateWaveformState(dt: Float, isPlaying: Boolean, audioLevel: Float) {
        val rawLevel = if (isPlaying) audioLevel.coerceIn(0f, 1f) else 0f
        val cleanedLevel =
            if (rawLevel <= WAVE_NOISE_FLOOR) {
                0f
            } else {
                ((rawLevel - WAVE_NOISE_FLOOR) / (1f - WAVE_NOISE_FLOOR)).coerceIn(0f, 1f)
            }
        val adaptiveDecay = if (isPlaying) 0.22f else 0.5f
        waveformAdaptivePeak =
            when {
                cleanedLevel > waveformAdaptivePeak -> cleanedLevel
                else -> max(cleanedLevel, waveformAdaptivePeak - dt * adaptiveDecay)
            }.coerceIn(0.035f, 1f)
        val normalizedLevel =
            if (cleanedLevel <= 0f) {
                0f
            } else {
                (cleanedLevel / waveformAdaptivePeak).coerceIn(0f, 1f)
            }
        val target =
            if (normalizedLevel <= 0f) {
                0f
            } else {
                (
                    normalizedLevel.pow(0.48f) * 0.92f +
                        cleanedLevel.pow(0.26f) * 0.32f
                    ).coerceIn(0f, 1f)
            }
        val response = if (target > waveformEnvelope) 1f - exp(-18f * dt) else 1f - exp(-5.5f * dt)
        val previousEnvelope = waveformEnvelope
        waveformEnvelope += (target - waveformEnvelope) * response
        val transient = (target - previousEnvelope).coerceAtLeast(0f)
        if (transient >= WAVE_BEAT_TRIGGER) {
            waveformBeatPulse = 1f
        }
        waveformBeatPulse = (waveformBeatPulse - dt * 1.9f).coerceAtLeast(0f)
        waveformPeak = max(target, waveformPeak - dt * 0.85f)
        waveformSampleTimer += dt

        while (waveformSampleTimer >= WAVE_SAMPLE_S) {
            waveformSampleTimer -= WAVE_SAMPLE_S
            val shaped =
                if (waveformEnvelope <= 0.002f) {
                    0f
                } else {
                    (
                        waveformEnvelope.pow(0.34f) * 0.92f +
                            transient.coerceIn(0f, 1f) * 0.42f +
                            waveformBeatPulse * 0.18f
                        ).coerceIn(0f, 1f)
                }
            pushWaveformSample(shaped)
        }

        if (!isPlaying && rawLevel <= 0f) {
            for (i in waveformHistory.indices) {
                waveformHistory[i] *= 0.86f
                if (waveformHistory[i] < 0.01f) {
                    waveformHistory[i] = 0f
                }
            }
            waveformEnvelope *= 0.84f
            waveformPeak *= 0.9f
            waveformAdaptivePeak = max(0.08f, waveformAdaptivePeak * 0.92f)
        }
    }

    private fun pushWaveformSample(value: Float) {
        for (i in 0 until WAVE_BARS - 1) {
            waveformHistory[i] = waveformHistory[i + 1]
        }
        waveformHistory[WAVE_BARS - 1] = value
    }

    private fun drawWaveform(x: Float, y: Float, w: Float, h: Float, c1: Int, c2: Int, isPlaying: Boolean) {
        val midY = y + h / 2f
        val now = waveformMotionTime
        val activity = if (isPlaying) 1f else 0.48f
        val centerColor = lerpColor(c1, c2, 0.5f)
        val baseAlpha = ((0x18 + waveformPeak * 0x28) * activity).toInt().coerceIn(0, 0x48)
        val pulseAlpha = ((0x10 + waveformBeatPulse * 0x36) * activity).toInt().coerceIn(0, 0x54)
        val layers = arrayOf(
            WaveLayerSpec(shiftHue(c1, -16f), -0.95f, 0.82f, 0.95f, 0.25f),
            WaveLayerSpec(c1, -0.25f, 1.08f, 1.12f, 0.42f),
            WaveLayerSpec(centerColor, 0.45f, 1.36f, 1.32f, 0.56f),
            WaveLayerSpec(c2, 1.05f, 1.68f, 1.06f, 0.38f),
            WaveLayerSpec(shiftHue(c2, 16f), 1.55f, 2.02f, 0.88f, 0.22f),
        )

        NVGRenderer.line(x + 2f, midY, x + w - 2f, midY, 1f, withAlpha(centerColor, baseAlpha))
        if (pulseAlpha > 0) {
            NVGRenderer.line(x + 6f, midY, x + w - 6f, midY, 2.3f, withAlpha(centerColor, pulseAlpha / 2))
        }

        for ((index, layer) in layers.withIndex()) {
            val alphaScale = if (index == 2) 1f else 0.78f
            drawWaveformLayer(
                x = x,
                midY = midY,
                w = w,
                h = h,
                now = now,
                spec = layer,
                activity = activity,
                alphaScale = alphaScale,
                beatAlpha = pulseAlpha,
            )
        }
    }

    private data class WaveLayerSpec(
        val color: Int,
        val phaseOffset: Float,
        val frequency: Float,
        val amplitudeScale: Float,
        val shimmerScale: Float,
    )

    private fun drawWaveformLayer(
        x: Float,
        midY: Float,
        w: Float,
        h: Float,
        now: Float,
        spec: WaveLayerSpec,
        activity: Float,
        alphaScale: Float,
        beatAlpha: Int,
    ) {
        val samples = WAVE_RENDER_SAMPLES
        val glowAlpha = ((0x16 + waveformPeak * 0x42) * activity * alphaScale).toInt().coerceIn(0, 0x54)
        val coreAlpha = ((0x46 + waveformEnvelope * 0x98 + waveformBeatPulse * 0x36) * activity * alphaScale).toInt().coerceIn(0, 0xFF)
        val accentAlpha = ((0x10 + beatAlpha * 0.3f) * alphaScale).toInt().coerceIn(0, 0x32)
        var prevX = x
        var prevY = midY

        for (sample in 0..samples) {
            val t = sample / samples.toFloat()
            val energy = sampleWaveform(t)
            val edge = sin(t * Math.PI.toFloat()).pow(0.92f)
            val ripple =
                sin(t * Math.PI.toFloat() * spec.frequency - now * (2.6f + spec.frequency * 0.45f) + spec.phaseOffset)
            val detail =
                sin(t * Math.PI.toFloat() * (spec.frequency * 1.9f + 0.8f) + now * (1.4f + spec.shimmerScale) - spec.phaseOffset * 0.7f)
            val sway =
                sin(now * (0.85f + spec.shimmerScale * 0.6f) + t * Math.PI.toFloat() * 2.3f + spec.phaseOffset) * 0.12f
            val heightScale =
                (0.08f + energy * (0.52f + spec.amplitudeScale * 0.18f) + waveformBeatPulse * 0.08f) *
                    edge *
                    activity
            val displacement =
                (ripple * 0.72f + detail * 0.22f + sway) *
                    (h * spec.amplitudeScale * heightScale)
            val px = x + t * w
            val py = midY + displacement

            if (sample > 0) {
                NVGRenderer.line(prevX, prevY, px, py, 4.6f, withAlpha(spec.color, glowAlpha))
                NVGRenderer.line(prevX, prevY, px, py, 2.15f, withAlpha(spec.color, coreAlpha))
                if (accentAlpha > 0 && sample % 6 == 0) {
                    NVGRenderer.circle(px, py, 1.3f, withAlpha(0xFFFFFFFF.toInt(), accentAlpha))
                }
            }

            prevX = px
            prevY = py
        }
    }

    private fun sampleWaveform(t: Float): Float {
        val scaled = t.coerceIn(0f, 1f) * (WAVE_BARS - 1)
        val start = scaled.toInt().coerceIn(0, WAVE_BARS - 1)
        val end = (start + 1).coerceAtMost(WAVE_BARS - 1)
        val mix = smoothStep(scaled - start)
        return (waveformHistory[start] + (waveformHistory[end] - waveformHistory[start]) * mix).coerceIn(0f, 1f)
    }

    // -- Art cache + dominant color refresh ------------------------------------

    private fun refreshArtCache(now: Long) {
        val ver = SpotifyPoller.artVersion
        if (ver == cachedArtVersion) return
        cachedArtVersion = ver

        val old = cachedArtImage
        if (old != null) {
            prevArtImage?.let { NVGRenderer.deleteImage(it) }
            prevArtImage   = old
            cachedArtImage = null
            artFadeStartMs = now
        }

        val path = SpotifyPoller.currentArtPath
        if (path.isNotEmpty()) {
            runCatching { cachedArtImage = NVGRenderer.createImage(path) }
            if (artFadeStartMs == 0L) artFadeStartMs = now
        }

        if (autoColorSetting.value && path.isNotEmpty() && ver != dominantArtVersion) {
            dominantArtVersion = ver
            Thread({
                val (dc1, dc2) = extractDominantColors(path)
                artDominantC1 = dc1
                artDominantC2 = dc2
            }, "art-color").also { it.isDaemon = true }.start()
        }
    }

    // -- Dominant color extraction ---------------------------------------------

    private fun extractDominantColors(path: String): Pair<Int, Int> {
        return runCatching {
            val img = javax.imageio.ImageIO.read(java.io.File(path))
                ?: return@runCatching 0xFF1DB954.toInt() to 0xFF9B59B6.toInt()
            val w = img.width; val h = img.height
            val step = maxOf(1, minOf(w, h) / 20)
            var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0
            var iy = 0
            while (iy < h) {
                var ix = 0
                while (ix < w) {
                    val rgb = img.getRGB(ix, iy)
                    rSum += (rgb shr 16) and 0xFF
                    gSum += (rgb shr 8) and 0xFF
                    bSum += rgb and 0xFF
                    count++
                    ix += step
                }
                iy += step
            }
            if (count == 0) return@runCatching 0xFF1DB954.toInt() to 0xFF9B59B6.toInt()
            val r = (rSum / count).toInt()
            val g = (gSum / count).toInt()
            val b = (bSum / count).toInt()
            val (bR, bG, bB) = boostSaturation(r, g, b, 1.6f)
            val c1 = (0xFF shl 24) or (bR shl 16) or (bG shl 8) or bB
            val c2 = shiftHue(bR, bG, bB, 35f)
            c1 to c2
        }.getOrElse { 0xFF1DB954.toInt() to 0xFF9B59B6.toInt() }
    }

    private fun boostSaturation(r: Int, g: Int, b: Int, factor: Float): Triple<Int, Int, Int> {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val grey = 0.299f * rf + 0.587f * gf + 0.114f * bf
        val nr = ((grey + (rf - grey) * factor) * 255f).toInt().coerceIn(0, 255)
        val ng = ((grey + (gf - grey) * factor) * 255f).toInt().coerceIn(0, 255)
        val nb = ((grey + (bf - grey) * factor) * 255f).toInt().coerceIn(0, 255)
        return Triple(nr, ng, nb)
    }

    private fun shiftHue(r: Int, g: Int, b: Int, degrees: Float): Int {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val max = maxOf(rf, gf, bf); val min = minOf(rf, gf, bf)
        val d = max - min
        if (d < 0.001f) return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        var h = when (max) {
            rf -> (gf - bf) / d + if (gf < bf) 6f else 0f
            gf -> (bf - rf) / d + 2f
            else -> (rf - gf) / d + 4f
        }
        h = ((h / 6f + degrees / 360f) % 1f + 1f) % 1f
        val s = d / max; val v = max
        val i = (h * 6f).toInt()
        val p = v * (1f - s); val q = v * (1f - s * (h * 6f - i))
        val t = v * (1f - s * (1f - (h * 6f - i)))
        val (nr, ng, nb) = when (i % 6) {
            0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t)
            3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q)
        }
        return (0xFF shl 24) or ((nr * 255).toInt().coerceIn(0, 255) shl 16) or
               ((ng * 255).toInt().coerceIn(0, 255) shl 8) or (nb * 255).toInt().coerceIn(0, 255)
    }

    // -- Helpers ---------------------------------------------------------------

    private fun drawScrollingTitle(text: String, x: Float, y: Float, maxW: Float, size: Float, color: Int, dt: Float) {
        val fullW = NVGRenderer.textWidth(text, size)
        if (fullW <= maxW) {
            NVGRenderer.textShadow(text, x, y, size, color)
            return
        }

        val scrollRange = fullW - maxW
        val scrollSpeed = 40f

        if (dt > 0f) {
            if (titleScrollPause > 0f) {
                titleScrollPause -= dt
            } else {
                titleScrollX += scrollSpeed * dt * titleScrollDir
                if (titleScrollX >= scrollRange) {
                    titleScrollX    = scrollRange
                    titleScrollDir  = -1f
                    titleScrollPause = 1.2f
                } else if (titleScrollX <= 0f) {
                    titleScrollX    = 0f
                    titleScrollDir  = 1f
                    titleScrollPause = 1.2f
                }
            }
        }

        NVGRenderer.pushScissor(x, y, maxW, size + 2f)
        NVGRenderer.textShadow(text, x - titleScrollX, y, size, color)
        NVGRenderer.popScissor()
    }

    private fun truncate(text: String, maxW: Float, size: Float): String {
        if (NVGRenderer.textWidth(text, size) <= maxW) return text
        var t = text
        while (t.isNotEmpty() && NVGRenderer.textWidth("$t...", size) > maxW) t = t.dropLast(1)
        return "$t..."
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val clamped = t.coerceIn(0f, 1f)
        val fa = (from ushr 24) and 0xFF
        val fr = (from ushr 16) and 0xFF
        val fg = (from ushr 8) and 0xFF
        val fb = from and 0xFF
        val ta = (to ushr 24) and 0xFF
        val tr = (to ushr 16) and 0xFF
        val tg = (to ushr 8) and 0xFF
        val tb = to and 0xFF

        val a = (fa + ((ta - fa) * clamped)).toInt().coerceIn(0, 255)
        val r = (fr + ((tr - fr) * clamped)).toInt().coerceIn(0, 255)
        val g = (fg + ((tg - fg) * clamped)).toInt().coerceIn(0, 255)
        val b = (fb + ((tb - fb) * clamped)).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun smoothStep(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
    }

    private fun shiftHue(color: Int, degrees: Float): Int {
        return shiftHue(
            (color ushr 16) and 0xFF,
            (color ushr 8) and 0xFF,
            color and 0xFF,
            degrees,
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return ((alpha.coerceIn(0, 255)) shl 24) or (color and 0x00FFFFFF)
    }

    private fun formatMs(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        return "%d:%02d".format(total / 60L, total % 60L)
    }
}
