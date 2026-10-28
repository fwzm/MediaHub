using System.Runtime.InteropServices;

namespace MediaHub.EngineProbe;

/// <summary>
/// The subset of the libmpv client API this probe needs, rebound from an arbitrary DLL.
/// </summary>
/// <remarks>
/// Entry point names and struct layouts mirror <c>windows/src/MediaHub.Player.Mpv/MpvNative.cs</c>
/// so the probe exercises the same surface the product uses. Strings are marshalled by hand as
/// null-terminated UTF-8 instead of relying on delegate string marshalling, because getting that
/// wrong on a reverse P/Invoke is silent corruption rather than a compile error.
/// </remarks>
internal sealed class MpvApi
{
    // ---- mpv_format ----
    public const int FormatFlag = 3;
    public const int FormatInt64 = 4;
    public const int FormatDouble = 5;

    // ---- mpv_event_id ----
    public const int EventNone = 0;
    public const int EventShutdown = 1;
    public const int EventLogMessage = 2;
    public const int EventStartFile = 6;
    public const int EventEndFile = 7;
    public const int EventFileLoaded = 8;

    // ---- mpv_end_file_reason ----
    public const int EndFileReasonEof = 0;
    public const int EndFileReasonStop = 2;
    public const int EndFileReasonQuit = 3;
    public const int EndFileReasonError = 4;

    private readonly ClientApiVersionFn _clientApiVersion;
    private readonly CreateFn _create;
    private readonly InitializeFn _initialize;
    private readonly SetStringFn _setOptionString;
    private readonly CommandFn _command;
    private readonly CommandStringFn _commandString;
    private readonly WaitEventFn _waitEvent;
    private readonly GetStringFn _getPropertyString;
    private readonly GetPropertyInt64Fn _getPropertyInt64;
    private readonly GetPropertyDoubleFn _getPropertyDouble;
    private readonly FreeFn _free;
    private readonly ErrorStringFn _errorString;
    private readonly RequestLogMessagesFn _requestLogMessages;
    private readonly TerminateDestroyFn _terminateDestroy;
    private readonly DestroyFn? _destroy;

    public MpvApi(MpvLibrary library)
    {
        _clientApiVersion = library.Bind<ClientApiVersionFn>("mpv_client_api_version");
        _create = library.Bind<CreateFn>("mpv_create");
        _initialize = library.Bind<InitializeFn>("mpv_initialize");
        _setOptionString = library.Bind<SetStringFn>("mpv_set_option_string");
        _command = library.Bind<CommandFn>("mpv_command");
        _commandString = library.Bind<CommandStringFn>("mpv_command_string");
        _waitEvent = library.Bind<WaitEventFn>("mpv_wait_event");
        _getPropertyString = library.Bind<GetStringFn>("mpv_get_property_string");
        _getPropertyInt64 = library.Bind<GetPropertyInt64Fn>("mpv_get_property");
        _getPropertyDouble = library.Bind<GetPropertyDoubleFn>("mpv_get_property");
        _free = library.Bind<FreeFn>("mpv_free");
        _errorString = library.Bind<ErrorStringFn>("mpv_error_string");
        _requestLogMessages = library.Bind<RequestLogMessagesFn>("mpv_request_log_messages");
        _terminateDestroy = library.Bind<TerminateDestroyFn>("mpv_terminate_destroy");
        // mpv_destroy is only a fallback for a build that lost terminate_destroy; the product never
        // calls it, so it stays optional instead of making the whole probe fail on a missing export.
        _destroy = library.HasExport("mpv_destroy") ? library.Bind<DestroyFn>("mpv_destroy") : null;
    }

    /// <summary>Packed <c>MPV_CLIENT_API_VERSION</c>; major in the high 16 bits.</summary>
    public uint ClientApiVersion() => _clientApiVersion();

    public IntPtr Create() => _create();

    public int Initialize(IntPtr context) => _initialize(context);

    public int SetOptionString(IntPtr context, string name, string value)
    {
        IntPtr namePtr = Marshal.StringToCoTaskMemUTF8(name);
        IntPtr valuePtr = Marshal.StringToCoTaskMemUTF8(value);
        try
        {
            return _setOptionString(context, namePtr, valuePtr);
        }
        finally
        {
            Marshal.FreeCoTaskMem(namePtr);
            Marshal.FreeCoTaskMem(valuePtr);
        }
    }

    public int RequestLogMessages(IntPtr context, string minimumLevel)
    {
        IntPtr levelPtr = Marshal.StringToCoTaskMemUTF8(minimumLevel);
        try
        {
            return _requestLogMessages(context, levelPtr);
        }
        finally
        {
            Marshal.FreeCoTaskMem(levelPtr);
        }
    }

    /// <summary>Runs a command from an argument vector; never call this from inside event handling.</summary>
    public int Command(IntPtr context, params string[] arguments)
    {
        var pointers = new IntPtr[arguments.Length + 1];
        try
        {
            for (int i = 0; i < arguments.Length; i++)
            {
                pointers[i] = Marshal.StringToCoTaskMemUTF8(arguments[i]);
            }

            pointers[arguments.Length] = IntPtr.Zero;
            IntPtr array = Marshal.AllocCoTaskMem(IntPtr.Size * pointers.Length);
            try
            {
                Marshal.Copy(pointers, 0, array, pointers.Length);
                return _command(context, array);
            }
            finally
            {
                Marshal.FreeCoTaskMem(array);
            }
        }
        finally
        {
            foreach (IntPtr pointer in pointers)
            {
                if (pointer != IntPtr.Zero)
                {
                    Marshal.FreeCoTaskMem(pointer);
                }
            }
        }
    }

    public int CommandString(IntPtr context, string command)
    {
        IntPtr textPtr = Marshal.StringToCoTaskMemUTF8(command);
        try
        {
            return _commandString(context, textPtr);
        }
        finally
        {
            Marshal.FreeCoTaskMem(textPtr);
        }
    }

    /// <summary>Blocks for at most <paramref name="timeoutSeconds"/>; returns a pointer to an <see cref="MpvEvent"/>.</summary>
    public IntPtr WaitEvent(IntPtr context, double timeoutSeconds) => _waitEvent(context, timeoutSeconds);

    public string? GetString(IntPtr context, string name)
    {
        IntPtr namePtr = Marshal.StringToCoTaskMemUTF8(name);
        try
        {
            IntPtr valuePtr = _getPropertyString(context, namePtr);
            if (valuePtr == IntPtr.Zero)
            {
                return null;
            }

            try
            {
                return Marshal.PtrToStringUTF8(valuePtr);
            }
            finally
            {
                _free(valuePtr);
            }
        }
        finally
        {
            Marshal.FreeCoTaskMem(namePtr);
        }
    }

    public double? GetDouble(IntPtr context, string name)
    {
        IntPtr namePtr = Marshal.StringToCoTaskMemUTF8(name);
        try
        {
            return _getPropertyDouble(context, namePtr, FormatDouble, out double value) >= 0 ? value : null;
        }
        finally
        {
            Marshal.FreeCoTaskMem(namePtr);
        }
    }

    public long? GetInt64(IntPtr context, string name)
    {
        IntPtr namePtr = Marshal.StringToCoTaskMemUTF8(name);
        try
        {
            return _getPropertyInt64(context, namePtr, FormatInt64, out long value) >= 0 ? value : null;
        }
        finally
        {
            Marshal.FreeCoTaskMem(namePtr);
        }
    }

    public bool? GetFlag(IntPtr context, string name)
    {
        IntPtr namePtr = Marshal.StringToCoTaskMemUTF8(name);
        try
        {
            return _getPropertyInt64(context, namePtr, FormatFlag, out long value) >= 0 ? value != 0 : null;
        }
        finally
        {
            Marshal.FreeCoTaskMem(namePtr);
        }
    }

    public string ErrorString(int error)
    {
        IntPtr pointer = _errorString(error);
        return pointer == IntPtr.Zero ? "mpv error " + error : MarshalledString(pointer) ?? "mpv error " + error;
    }

    public void TerminateDestroy(IntPtr context) => _terminateDestroy(context);

    /// <summary>Calls <c>mpv_destroy</c> when the build exports it; used only as a shutdown fallback.</summary>
    public bool TryDestroy(IntPtr context)
    {
        if (_destroy is null)
        {
            return false;
        }

        _destroy(context);
        return true;
    }

    public static MpvEvent ReadEvent(IntPtr pointer) => Marshal.PtrToStructure<MpvEvent>(pointer);

    public static MpvEventEndFile ReadEndFile(IntPtr pointer) => Marshal.PtrToStructure<MpvEventEndFile>(pointer);

    public static MpvEventLogMessage ReadLogMessage(IntPtr pointer) => Marshal.PtrToStructure<MpvEventLogMessage>(pointer);

    /// <summary>Copies a libmpv-owned UTF-8 string without freeing it.</summary>
    public static string? MarshalledString(IntPtr pointer) => pointer == IntPtr.Zero ? null : Marshal.PtrToStringUTF8(pointer);

    [StructLayout(LayoutKind.Sequential)]
    internal struct MpvEvent
    {
        public int EventId;
        public int Error;
        public ulong ReplyUserData;
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

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate uint ClientApiVersionFn();

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate IntPtr CreateFn();

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int InitializeFn(IntPtr context);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int SetStringFn(IntPtr context, IntPtr name, IntPtr value);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int CommandFn(IntPtr context, IntPtr arguments);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int CommandStringFn(IntPtr context, IntPtr command);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate IntPtr WaitEventFn(IntPtr context, double timeoutSeconds);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate IntPtr GetStringFn(IntPtr context, IntPtr name);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int GetPropertyInt64Fn(IntPtr context, IntPtr name, int format, out long value);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int GetPropertyDoubleFn(IntPtr context, IntPtr name, int format, out double value);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate void FreeFn(IntPtr data);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate IntPtr ErrorStringFn(int error);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int RequestLogMessagesFn(IntPtr context, IntPtr minimumLevel);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate void TerminateDestroyFn(IntPtr context);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate void DestroyFn(IntPtr context);
}
