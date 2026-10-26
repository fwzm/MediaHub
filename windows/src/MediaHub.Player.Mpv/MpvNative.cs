using System.Runtime.InteropServices;

namespace MediaHub.Player.Mpv;

/// <summary>
/// Raw P/Invoke surface for libmpv. Kept internal so no other assembly can bypass the
/// lifetime and thread-affinity rules enforced by <see cref="MpvPlayer"/>.
/// </summary>
/// <remarks>
/// Threading contract (mpv client.h): the handle may be used from any thread, but
/// <c>mpv_wait_event</c> must only run on one thread at a time, and mpv commands must never be
/// issued from inside the event-handling path. <see cref="MpvPlayer"/> satisfies both rules.
/// </remarks>
internal static class MpvNative
{
    internal const string Lib = "libmpv-2.dll";

    // ---- mpv_format ----
    internal const int MPV_FORMAT_NONE = 0;
    internal const int MPV_FORMAT_STRING = 1;
    internal const int MPV_FORMAT_OSD_STRING = 2;
    internal const int MPV_FORMAT_FLAG = 3;
    internal const int MPV_FORMAT_INT64 = 4;
    internal const int MPV_FORMAT_DOUBLE = 5;
    internal const int MPV_FORMAT_NODE = 6;

    // ---- mpv_event_id ----
    internal const int MPV_EVENT_NONE = 0;
    internal const int MPV_EVENT_SHUTDOWN = 1;
    internal const int MPV_EVENT_LOG_MESSAGE = 2;
    internal const int MPV_EVENT_GET_PROPERTY_REPLY = 3;
    internal const int MPV_EVENT_SET_PROPERTY_REPLY = 4;
    internal const int MPV_EVENT_COMMAND_REPLY = 5;
    internal const int MPV_EVENT_START_FILE = 6;
    internal const int MPV_EVENT_END_FILE = 7;
    internal const int MPV_EVENT_FILE_LOADED = 8;
    internal const int MPV_EVENT_CLIENT_MESSAGE = 16;
    internal const int MPV_EVENT_VIDEO_RECONFIG = 17;
    internal const int MPV_EVENT_AUDIO_RECONFIG = 18;
    internal const int MPV_EVENT_SEEK = 20;
    internal const int MPV_EVENT_PLAYBACK_RESTART = 21;
    internal const int MPV_EVENT_PROPERTY_CHANGE = 22;
    internal const int MPV_EVENT_QUEUE_OVERFLOW = 24;
    internal const int MPV_EVENT_HOOK = 25;

    // ---- mpv_end_file_reason ----
    internal const int MPV_END_FILE_REASON_EOF = 0;
    internal const int MPV_END_FILE_REASON_STOP = 2;
    internal const int MPV_END_FILE_REASON_QUIT = 3;
    internal const int MPV_END_FILE_REASON_ERROR = 4;

    [StructLayout(LayoutKind.Sequential)]
    internal struct MpvEvent
    {
        public int EventId;
        public int Error;
        public ulong ReplyUserData;
        public IntPtr Data;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct MpvEventProperty
    {
        public IntPtr Name;
        public int Format;
        public IntPtr Data;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct MpvEventEndFile
    {
        public int Reason;
        public int Error;
        public long PlaylistEntryId;
        public long PlaylistInsertId;
        public int PlaylistInsertNumEntries;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct MpvEventLogMessage
    {
        public IntPtr Prefix;
        public IntPtr Level;
        public IntPtr Text;
        public int LogLevel;
    }

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    internal static extern IntPtr mpv_create();

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    internal static extern int mpv_initialize(IntPtr ctx);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    internal static extern void mpv_terminate_destroy(IntPtr ctx);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    internal static extern int mpv_set_option_string(IntPtr ctx, [MarshalAs(UnmanagedType.LPUTF8Str)] string name, [MarshalAs(UnmanagedType.LPUTF8Str)] string value);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    internal static extern int mpv_set_property_string(IntPtr ctx, [MarshalAs(UnmanagedType.LPUTF8Str)] string name, [MarshalAs(UnmanagedType.LPUTF8Str)] string value);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    internal static extern IntPtr mpv_get_property_string(IntPtr ctx, [MarshalAs(UnmanagedType.LPUTF8Str)] string name);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    internal static extern int mpv_set_property(IntPtr ctx, [MarshalAs(UnmanagedType.LPUTF8Str)] string name, int format, ref long data);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    internal static extern int mpv_set_property(IntPtr ctx, [MarshalAs(UnmanagedType.LPUTF8Str)] string name, int format, ref double data);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    internal static extern int mpv_get_property(IntPtr ctx, [MarshalAs(UnmanagedType.LPUTF8Str)] string name, int format, out long data);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    internal static extern int mpv_get_property(IntPtr ctx, [MarshalAs(UnmanagedType.LPUTF8Str)] string name, int format, out double data);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    internal static extern int mpv_command(IntPtr ctx, IntPtr args);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    internal static extern int mpv_command_string(IntPtr ctx, [MarshalAs(UnmanagedType.LPUTF8Str)] string args);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    internal static extern IntPtr mpv_wait_event(IntPtr ctx, double timeout);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    internal static extern IntPtr mpv_error_string(int error);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    internal static extern int mpv_observe_property(IntPtr ctx, ulong replyUserData, [MarshalAs(UnmanagedType.LPUTF8Str)] string name, int format);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    internal static extern int mpv_unobserve_property(IntPtr ctx, ulong replyUserData);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    internal static extern void mpv_free(IntPtr data);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    internal static extern IntPtr mpv_client_api_version();

    internal static string ErrorString(int error)
    {
        IntPtr p = mpv_error_string(error);
        return p == IntPtr.Zero ? $"mpv error {error}" : (Marshal.PtrToStringUTF8(p) ?? $"mpv error {error}");
    }

    /// <summary>Reads a string property; returns null when unset (mpv returns NULL for unset props).</summary>
    internal static string? GetString(IntPtr ctx, string name)
    {
        IntPtr p = mpv_get_property_string(ctx, name);
        if (p == IntPtr.Zero) return null;
        try { return Marshal.PtrToStringUTF8(p); }
        finally { mpv_free(p); }
    }

    internal static long? GetInt64(IntPtr ctx, string name)
    {
        int rc = mpv_get_property(ctx, name, MPV_FORMAT_INT64, out long v);
        return rc >= 0 ? v : null;
    }

    internal static double? GetDouble(IntPtr ctx, string name)
    {
        int rc = mpv_get_property(ctx, name, MPV_FORMAT_DOUBLE, out double v);
        return rc >= 0 ? v : null;
    }

    internal static bool? GetFlag(IntPtr ctx, string name)
    {
        int rc = mpv_get_property(ctx, name, MPV_FORMAT_FLAG, out long v);
        return rc >= 0 ? v != 0 : null;
    }

    /// <summary>
    /// Issues a command built from a UTF-8 argument vector. Callers must not invoke this from the
    /// event-handling path (mpv holds its internal lock there and would deadlock).
    /// </summary>
    internal static int Command(IntPtr ctx, params string[] args)
    {
        var ptrs = new IntPtr[args.Length + 1];
        try
        {
            for (int i = 0; i < args.Length; i++) ptrs[i] = Marshal.StringToCoTaskMemUTF8(args[i]);
            ptrs[args.Length] = IntPtr.Zero;
            var handle = GCHandle.Alloc(ptrs, GCHandleType.Pinned);
            try { return mpv_command(ctx, handle.AddrOfPinnedObject()); }
            finally { handle.Free(); }
        }
        finally
        {
            for (int i = 0; i < args.Length; i++)
            {
                if (ptrs[i] != IntPtr.Zero) Marshal.FreeCoTaskMem(ptrs[i]);
            }
        }
    }
}
