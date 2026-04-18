package org.cobalt.internal.spotify

import java.io.File
import kotlin.math.pow

data class SpotifyTrack(
    val name: String,
    val artist: String,
    val progressMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val snapshotMs: Long = System.currentTimeMillis(),
) {
    /** Smoothly interpolated playback position in ms. */
    val currentProgressMs: Long
        get() = if (isPlaying)
            (progressMs + (System.currentTimeMillis() - snapshotMs)).coerceAtMost(durationMs)
        else
            progressMs
}

object SpotifyPoller {

    @Volatile var current: SpotifyTrack? = null
    @Volatile var audioLevel: Float = 0f

    /** Absolute path to the cached album-art image, or "" if not yet available. */
    @Volatile var currentArtPath: String = ""
    /** Incremented each time a new art file is written - used to detect changes. */
    @Volatile var artVersion: Int = 0

    private const val POLL_MS = 2_000L
    @Volatile private var lastPollMs = 0L
    @Volatile private var polling = false
    @Volatile private var meterReaderStarted = false
    @Volatile private var meterProcess: Process? = null

    private var lastArtTrack = ""
    private var artFileCounter = 0

    private val isWindows = System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)

    // Written once to a stable temp path; reused on every poll to avoid repeated I/O.
    private val scriptFile: File by lazy {
        File(System.getProperty("java.io.tmpdir"), "cobalt_spotify_smtc.ps1").also { f ->
            f.writeText(PS_SCRIPT)
        }
    }

    private val meterScriptFile: File by lazy {
        File(System.getProperty("java.io.tmpdir"), "cobalt_spotify_meter.ps1").also { f ->
            f.writeText(PS_METER_SCRIPT)
        }
    }

    // PowerShell script that reads the currently-playing track via Windows SMTC.
    // Outputs: Title|Artist|PositionMs|DurationMs|IsPlaying|ArtPath  - or "NONE" / "ERROR".
    // Art is extracted directly from the SMTC session thumbnail (no iTunes lookup needed).
    private val PS_SCRIPT = """
Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null
${'$'}helper = [System.WindowsRuntimeSystemExtensions]
${'$'}asTask = (${'$'}helper.GetMethods() | Where-Object { ${'$'}_.Name -eq 'AsTask' -and ${'$'}_.IsGenericMethodDefinition -and ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await(${'$'}op, ${'$'}type) {
    ${'$'}t = ${'$'}asTask.MakeGenericMethod(${'$'}type).Invoke(${'$'}null, ${'$'}op)
    ${'$'}t.Wait() | Out-Null
    ${'$'}t.Result
}
try {
    ${'$'}mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]::RequestAsync()) `
              ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime])
    ${'$'}sessions = ${'$'}mgr.GetSessions()
    ${'$'}s = ${'$'}null
    foreach (${'$'}sess in ${'$'}sessions) {
        ${'$'}pb2 = ${'$'}sess.GetPlaybackInfo()
        if (${'$'}pb2.PlaybackStatus -eq 'Playing' -or ${'$'}pb2.PlaybackStatus -eq 'Paused') {
            if (${'$'}sess.SourceAppUserModelId -like '*spotify*') { ${'$'}s = ${'$'}sess; break }
            if (${'$'}null -eq ${'$'}s) { ${'$'}s = ${'$'}sess }
        }
    }
    if (${'$'}null -eq ${'$'}s) { 'NONE'; exit }
    ${'$'}p  = Await (${'$'}s.TryGetMediaPropertiesAsync()) `
              ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties,Windows.Media.Control,ContentType=WindowsRuntime])
    ${'$'}pb = ${'$'}s.GetPlaybackInfo()
    ${'$'}tl = ${'$'}s.GetTimelineProperties()
    ${'$'}artOut = ""
    try {
        if (${'$'}null -ne ${'$'}p.Thumbnail) {
            ${'$'}strm = Await (${'$'}p.Thumbnail.OpenReadAsync()) `
                        ([Windows.Storage.Streams.IRandomAccessStreamWithContentType,Windows.Storage.Streams,ContentType=WindowsRuntime])
            ${'$'}asRead = ([System.IO.WindowsRuntimeStreamExtensions].GetMethods() | Where-Object { ${'$'}_.Name -eq 'AsStreamForRead' -and ${'$'}_.GetParameters().Count -eq 1 })[0]
            ${'$'}netStream = ${'$'}asRead.Invoke(${'$'}null, @(${'$'}strm))
            ${'$'}artOut = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), "cobalt_smtc_art.jpg")
            ${'$'}fs = [System.IO.File]::OpenWrite(${'$'}artOut)
            ${'$'}netStream.CopyTo(${'$'}fs)
            ${'$'}fs.Close()
            ${'$'}netStream.Close()
        }
    } catch { ${'$'}artOut = "" }
    "$(${'$'}p.Title)|$(${'$'}p.Artist)|$([long]${'$'}tl.Position.TotalMilliseconds)|$([long]${'$'}tl.EndTime.TotalMilliseconds)|$(${'$'}pb.PlaybackStatus -eq 'Playing')|$(${'$'}artOut)"
} catch { 'ERROR' }
    """.trimIndent()

    /** Call from tick - kicks off an async poll when the interval has elapsed. */
    fun update() {
        if (!isWindows) return
        ensureMeterReader()
        val now = System.currentTimeMillis()
        if (now - lastPollMs < POLL_MS || polling) return
        lastPollMs = now
        polling = true
        Thread({
            try { poll() } catch (_: Exception) {}
            finally { polling = false }
        }, "spotify-poll").also { it.isDaemon = true }.start()
    }

    /**
     * Send a media control key to the OS (system-wide).
     * Common VK codes: 0xB3 = Play/Pause, 0xB0 = Next Track, 0xB1 = Prev Track.
     * After sending, forces a re-poll so the HUD updates immediately.
     */
    fun sendCommand(vkCode: Int) {
        if (!isWindows) return
        Thread({
            try {
                val script = """
Add-Type -TypeDefinition @'
using System.Runtime.InteropServices;
public class CobaltKbd {
    [DllImport("user32.dll")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, uint dwExtraInfo);
}
'@
CobaltKbd::keybd_event($vkCode, 0, 0, 0)
CobaltKbd::keybd_event($vkCode, 0, 2, 0)
                """.trimIndent()
                ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script
                ).redirectErrorStream(true).start().waitFor()
                Thread.sleep(600)
                lastPollMs = 0L  // force re-poll so HUD reflects new play state
            } catch (_: Exception) {}
        }, "spotify-cmd").also { it.isDaemon = true }.start()
    }

    private fun poll() {
        val proc = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", scriptFile.absolutePath
        ).redirectErrorStream(true).start()

        val output = proc.inputStream.bufferedReader().readText().trim()
        // Snapshot AFTER receiving data so the interpolation baseline matches "when we
        // learned the position", not "when we started asking" (script takes 100-400ms).
        // This keeps the display at or slightly behind actual, never ahead.
        val snapshotMs = System.currentTimeMillis()
        proc.waitFor()

        val rawTrack = when {
            output.isEmpty() || output == "NONE" || output == "ERROR" -> null
            else -> parseOutput(output, snapshotMs)
        }

        // Ratchet: for the same playing track, if SMTC lag causes the new interpolated
        // position to fall behind what's already displayed, carry the current value forward
        // rather than snapping backward.  Only applies to small differences (< 3 s) so
        // intentional seeks (large jumps) are still honoured.
        val track = rawTrack?.let { t ->
            val prev = current
            if (prev != null && t.name == prev.name && t.artist == prev.artist && t.isPlaying) {
                val prevMs = prev.currentProgressMs
                val newMs  = t.currentProgressMs
                if (newMs < prevMs && prevMs - newMs < 3_000L)
                    t.copy(progressMs = prevMs, snapshotMs = snapshotMs)
                else t
            } else t
        }
        current = track
        if (track == null || !track.isPlaying) {
            audioLevel *= 0.6f
        }

        // Update album art when the track changes
        val trackKey = track?.let { "${it.name}|${it.artist}" } ?: ""
        if (trackKey != lastArtTrack) {
            lastArtTrack = trackKey
            if (track != null) {
                // Always fetch clean art from iTunes (no branding watermarks)
                Thread({ fetchArt(track.artist, track.name) }, "art-fetch")
                    .also { it.isDaemon = true }.start()
            } else {
                currentArtPath = ""
                artVersion++
            }
        }
    }

    private fun fetchArt(artist: String, title: String) {
        try {
            val q = java.net.URLEncoder.encode("$artist $title", "UTF-8")

            // Try Deezer first - clean art, no watermarks, no auth needed
            var artUrl: String? = null
            runCatching {
                val deezerJson = java.net.URL("https://api.deezer.com/search?q=$q&limit=5").readText()
                val root = com.google.gson.JsonParser.parseString(deezerJson).asJsonObject
                val data = root.getAsJsonArray("data")
                if (data != null && data.size() > 0) {
                    artUrl = data[0].asJsonObject.getAsJsonObject("album")?.get("cover_big")?.asString
                }
            }

            // Fallback to iTunes
            if (artUrl == null) {
                runCatching {
                    val itunesJson = java.net.URL("https://itunes.apple.com/search?term=$q&media=music&limit=1&entity=song").readText()
                    val root = com.google.gson.JsonParser.parseString(itunesJson).asJsonObject
                    val results = root.getAsJsonArray("results")
                    if (results != null && results.size() > 0) {
                        artUrl = results[0].asJsonObject.get("artworkUrl100")?.asString
                            ?.replace("100x100bb", "600x600bb")
                    }
                }
            }

            artUrl ?: return
            val bytes = java.net.URL(artUrl).readBytes()
            artFileCounter++
            File(System.getProperty("java.io.tmpdir"), "cobalt_art_${artFileCounter - 1}.jpg").delete()
            val f = File(System.getProperty("java.io.tmpdir"), "cobalt_art_$artFileCounter.jpg")
            f.writeBytes(bytes)
            currentArtPath = f.absolutePath
            artVersion     = artFileCounter
        } catch (_: Exception) {}
    }

    private fun parseOutput(raw: String, snapshotMs: Long): SpotifyTrack? {
        val parts = raw.split("|")
        if (parts.size < 5) return null
        return runCatching {
            SpotifyTrack(
                name       = parts[0],
                artist     = parts[1],
                progressMs = parts[2].toLong(),
                durationMs = parts[3].toLong().coerceAtLeast(1L),
                isPlaying  = parts[4].trim().equals("True", ignoreCase = true),
                snapshotMs = snapshotMs,
            )
        }.getOrNull()
    }

    private fun ensureMeterReader() {
        if (meterReaderStarted) return
        synchronized(this) {
            if (meterReaderStarted) return
            meterReaderStarted = true
        }

        Thread({
            while (isWindows) {
                try {
                    val proc = ProcessBuilder(
                        "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                        "-File", meterScriptFile.absolutePath
                    ).redirectErrorStream(true).start()
                    meterProcess = proc

                    proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            val raw = line.trim()
                            val parsed = raw.replace(',', '.').toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
                            val leveled =
                                if (parsed <= 0.00005f) {
                                    0f
                                } else {
                                    val normalized = ((parsed - 0.00005f) / (1f - 0.00005f)).coerceIn(0f, 1f)
                                    val curved = normalized.toDouble().pow(0.34).toFloat()
                                    (normalized * 0.38f + curved * 0.96f).coerceIn(0f, 1f)
                                }
                            val prev = audioLevel
                            val blend =
                                when {
                                    leveled > prev -> 0.9f
                                    leveled > prev * 0.6f -> 0.48f
                                    else -> 0.24f
                                }
                            audioLevel = prev + (leveled - prev) * blend
                        }
                    }

                    proc.waitFor()
                } catch (_: Exception) {
                    audioLevel = 0f
                } finally {
                    meterProcess = null
                }

                try {
                    Thread.sleep(900L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
        }, "spotify-meter").also { it.isDaemon = true }.start()
    }

    private val PS_METER_SCRIPT = """
Add-Type -Language CSharp -TypeDefinition @'
using System;
using System.Diagnostics;
using System.Runtime.InteropServices;

[ComImport]
[Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
public class MMDeviceEnumeratorComObject {}

public enum EDataFlow {
    eRender,
    eCapture,
    eAll
}

public enum ERole {
    eConsole,
    eMultimedia,
    eCommunications
}

[Flags]
public enum CLSCTX : uint {
    INPROC_SERVER = 0x1,
    INPROC_HANDLER = 0x2,
    LOCAL_SERVER = 0x4,
    REMOTE_SERVER = 0x10,
    ALL = INPROC_SERVER | INPROC_HANDLER | LOCAL_SERVER | REMOTE_SERVER
}

[ComImport]
[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IMMDeviceEnumerator {
    int EnumAudioEndpoints(EDataFlow dataFlow, uint dwStateMask, out IntPtr ppDevices);
    int GetDefaultAudioEndpoint(EDataFlow dataFlow, ERole role, out IMMDevice ppDevice);
    int GetDevice(string pwstrId, out IMMDevice ppDevice);
    int RegisterEndpointNotificationCallback(IntPtr pClient);
    int UnregisterEndpointNotificationCallback(IntPtr pClient);
}

[ComImport]
[Guid("D666063F-1587-4E43-81F1-B948E807363F")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IMMDevice {
    int Activate(ref Guid iid, CLSCTX dwClsCtx, IntPtr pActivationParams, [MarshalAs(UnmanagedType.IUnknown)] out object ppInterface);
    int OpenPropertyStore(int stgmAccess, out IntPtr ppProperties);
    int GetId([MarshalAs(UnmanagedType.LPWStr)] out string ppstrId);
    int GetState(out int pdwState);
}

[ComImport]
[Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IAudioSessionManager2 {
    int GetAudioSessionControl(IntPtr AudioSessionGuid, uint StreamFlags, out IntPtr SessionControl);
    int GetSimpleAudioVolume(IntPtr AudioSessionGuid, uint StreamFlags, out IntPtr AudioVolume);
    int GetSessionEnumerator(out IAudioSessionEnumerator SessionEnum);
    int RegisterSessionNotification(IntPtr SessionNotification);
    int UnregisterSessionNotification(IntPtr SessionNotification);
    int RegisterDuckNotification(string sessionID, IntPtr duckNotification);
    int UnregisterDuckNotification(IntPtr duckNotification);
}

[ComImport]
[Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IAudioSessionEnumerator {
    int GetCount(out int SessionCount);
    int GetSession(int SessionCount, out IAudioSessionControl Session);
}

[ComImport]
[Guid("F4B1A599-7266-4319-A8CA-E70ACB11E8CD")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IAudioSessionControl {
    int GetState(out int pRetVal);
    int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string pRetVal);
    int SetDisplayName([MarshalAs(UnmanagedType.LPWStr)] string Value, ref Guid EventContext);
    int GetIconPath([MarshalAs(UnmanagedType.LPWStr)] out string pRetVal);
    int SetIconPath([MarshalAs(UnmanagedType.LPWStr)] string Value, ref Guid EventContext);
    int GetGroupingParam(out Guid pRetVal);
    int SetGroupingParam(ref Guid Override, ref Guid EventContext);
    int RegisterAudioSessionNotification(IntPtr NewNotifications);
    int UnregisterAudioSessionNotification(IntPtr NewNotifications);
}

[ComImport]
[Guid("bfb7ff88-7239-4fc9-8fa2-07c950be9c6d")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IAudioSessionControl2 : IAudioSessionControl {
    int GetSessionIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string pRetVal);
    int GetSessionInstanceIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string pRetVal);
    int GetProcessId(out uint pRetVal);
    int IsSystemSoundsSession();
    int SetDuckingPreference(bool optOut);
}

[ComImport]
[Guid("C02216F6-8C67-4B5B-9D00-D008E73E0064")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IAudioMeterInformation {
    int GetPeakValue(out float pfPeak);
    int GetMeteringChannelCount(out int pnChannelCount);
    int GetChannelsPeakValues(int u32ChannelCount, [Out, MarshalAs(UnmanagedType.LPArray, SizeParamIndex = 0)] float[] afPeakValues);
    int QueryHardwareSupport(out int pdwHardwareSupportMask);
}

public static class SpotifyAudioMeter {
    public static float GetSpotifyPeak() {
        IMMDeviceEnumerator enumerator = null;
        IMMDevice device = null;
        object managerObj = null;
        IAudioSessionEnumerator sessions = null;
        try {
            enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorComObject();
            Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(EDataFlow.eRender, ERole.eMultimedia, out device));

            Guid iid = typeof(IAudioSessionManager2).GUID;
            Marshal.ThrowExceptionForHR(device.Activate(ref iid, CLSCTX.ALL, IntPtr.Zero, out managerObj));
            var manager = (IAudioSessionManager2)managerObj;

            Marshal.ThrowExceptionForHR(manager.GetSessionEnumerator(out sessions));
            int count;
            Marshal.ThrowExceptionForHR(sessions.GetCount(out count));

            float bestPeak = 0f;

            for (int i = 0; i < count; i++) {
                IAudioSessionControl session = null;
                try {
                    Marshal.ThrowExceptionForHR(sessions.GetSession(i, out session));
                    var session2 = session as IAudioSessionControl2;
                    if (session2 == null) {
                        continue;
                    }

                    uint pid;
                    if (session2.GetProcessId(out pid) != 0 || pid == 0) {
                        continue;
                    }

                    Process process;
                    try {
                        process = Process.GetProcessById((int)pid);
                    } catch {
                        continue;
                    }

                    string processName = process.ProcessName ?? string.Empty;
                    string sessionDisplayName = string.Empty;
                    string sessionIdentifier = string.Empty;
                    try { session.GetDisplayName(out sessionDisplayName); } catch {}
                    try { session2.GetSessionIdentifier(out sessionIdentifier); } catch {}

                    bool isSpotifySession =
                        processName.IndexOf("spotify", StringComparison.OrdinalIgnoreCase) >= 0 ||
                        sessionDisplayName.IndexOf("spotify", StringComparison.OrdinalIgnoreCase) >= 0 ||
                        sessionIdentifier.IndexOf("spotify", StringComparison.OrdinalIgnoreCase) >= 0;

                    if (!isSpotifySession) {
                        continue;
                    }

                    var meter = session as IAudioMeterInformation;
                    if (meter == null) {
                        continue;
                    }

                    float peak;
                    if (meter.GetPeakValue(out peak) == 0 && peak > bestPeak) {
                        bestPeak = peak;
                    }
                } catch {
                } finally {
                    Release(session);
                }
            }

            return bestPeak;
        } catch {
            return 0f;
        } finally {
            Release(sessions);
            Release(managerObj);
            Release(device);
            Release(enumerator);
        }
    }

    private static void Release(object obj) {
        try {
            if (obj != null && Marshal.IsComObject(obj)) {
                Marshal.ReleaseComObject(obj);
            }
        } catch {
        }
    }
}
'@

while (${'$'}true) {
    try {
        ${'$'}peak = [SpotifyAudioMeter]::GetSpotifyPeak()
        [Console]::WriteLine(${'$'}peak.ToString([System.Globalization.CultureInfo]::InvariantCulture))
    } catch {
        [Console]::WriteLine("0")
    }
    Start-Sleep -Milliseconds 125
}
    """.trimIndent()
}
