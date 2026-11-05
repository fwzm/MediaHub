<#
.SYNOPSIS
Reports the peak audio level of a process's Windows audio session (default render endpoint).

.DESCRIPTION
Why this exists: the client exposes no mute state to UI Automation - the mute button's only
feedback is a FontIcon glyph swap, and the embedded engine has no IPC channel to query. Asking
the audio stack what the process is actually emitting is the difference between "the click did not
crash" and "the sound stopped". That is rule 1 of this project: a green step is not a tested thing.

Run in its own process (the harness invokes it with pwsh -File) so a COM interop mistake cannot take
down the harness itself.

.OUTPUT
PEAK=<max peak over the sampling window>  SESSIONS=<n>  STATE=<...>
#>
param(
  [Parameter(Mandatory)][int]$ProcessId,
  [int]$Samples = 24,
  [int]$IntervalMs = 60
)
$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Threading;

namespace MhAudio
{
    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    public class MMDeviceEnumerator { }

    [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IMMDeviceEnumerator
    {
        void EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        void GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
        void GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice device);
        void RegisterEndpointNotificationCallback(IntPtr client);
        void UnregisterEndpointNotificationCallback(IntPtr client);
    }

    [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IMMDevice
    {
        void Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
        void OpenPropertyStore(int stgmAccess, out IntPtr properties);
        void GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        void GetState(out int state);
    }

    [Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IAudioSessionManager2
    {
        void GetAudioSessionControl(IntPtr sessionGuid, int streamFlags, out IntPtr control);
        void GetSimpleAudioVolume(IntPtr sessionGuid, int streamFlags, out IntPtr volume);
        void GetSessionEnumerator(out IAudioSessionEnumerator enumerator);
        void RegisterSessionNotification(IntPtr notification);
        void UnregisterSessionNotification(IntPtr notification);
        void RegisterDuckNotification([MarshalAs(UnmanagedType.LPWStr)] string sessionId, IntPtr notification);
        void UnregisterDuckNotification(IntPtr notification);
    }

    [Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IAudioSessionEnumerator
    {
        void GetCount(out int count);
        void GetSession(int index, out IAudioSessionControl2 session);
    }

    [Guid("BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IAudioSessionControl2
    {
        void GetState(out int state);
        void GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string name);
        void SetDisplayName([MarshalAs(UnmanagedType.LPWStr)] string name);
        void GetIconPath([MarshalAs(UnmanagedType.LPWStr)] out string path);
        void SetIconPath([MarshalAs(UnmanagedType.LPWStr)] string path);
        void GetGroupingParam(out Guid param);
        void SetGroupingParam(ref Guid param, IntPtr context);
        void RegisterAudioSessionNotification(IntPtr client);
        void UnregisterAudioSessionNotification(IntPtr client);
        void GetSessionIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
        void GetSessionInstanceIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
        void GetProcessId(out int pid);
        void IsSystemSoundsSession();
        void SetDuckingPreference(bool optOut);
    }

    [Guid("C02216F6-8C67-4B5B-9D00-D008E73E0064"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IAudioMeterInformation
    {
        void GetPeakValue(out float peak);
    }

    public static class Peak
    {
        public static string Sample(int pid, int samples, int intervalMs)
        {
            var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumerator());
            IMMDevice device;
            enumerator.GetDefaultAudioEndpoint(0, 0, out device);
            object raw;
            Guid iid = typeof(IAudioSessionManager2).GUID;
            device.Activate(ref iid, 23, IntPtr.Zero, out raw);
            var manager = (IAudioSessionManager2)raw;
            IAudioSessionEnumerator sessions;
            manager.GetSessionEnumerator(out sessions);
            int count;
            sessions.GetCount(out count);
            IAudioMeterInformation meter = null;
            int state = -1;
            for (int i = 0; i < count; i++)
            {
                IAudioSessionControl2 session;
                sessions.GetSession(i, out session);
                int sessionPid;
                session.GetProcessId(out sessionPid);
                if (sessionPid != pid) continue;
                session.GetState(out state);
                meter = (IAudioMeterInformation)session;
            }
            if (meter == null) return "PEAK=NO_SESSION SESSIONS=" + count;
            float max = 0f;
            for (int s = 0; s < samples; s++)
            {
                float peak;
                meter.GetPeakValue(out peak);
                if (peak > max) max = peak;
                Thread.Sleep(intervalMs);
            }
            return "PEAK=" + max.ToString("F6", System.Globalization.CultureInfo.InvariantCulture)
                 + " SESSIONS=" + count + " STATE=" + state;
        }
    }
}
'@

[MhAudio.Peak]::Sample($ProcessId, $Samples, $IntervalMs)
