namespace MediaHub.SyncCli;

using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Unicode;

/// <summary>Builds the single JSON document that <c>--json</c> guarantees on stdout.</summary>
public static class CliJson
{
    /// <summary>Runs <paramref name="body"/> against a compact UTF-8 writer and returns the text.</summary>
    public static string Write(Action<Utf8JsonWriter> body)
    {
        ArgumentNullException.ThrowIfNull(body);
        using MemoryStream buffer = new();
        using (Utf8JsonWriter writer = new(buffer, new JsonWriterOptions
            {
                Indented = false,
                Encoder = JavaScriptEncoder.Create(UnicodeRanges.All),
            }))
        {
            body(writer);
        }

        return Encoding.UTF8.GetString(buffer.ToArray());
    }

    /// <summary>Writes the shared failure shape: <c>{"ok":false,"error":{"code":"…","message":"…"}}</c>.</summary>
    public static void WriteError(Utf8JsonWriter writer, string code, string message)
    {
        writer.WriteStartObject();
        writer.WriteBoolean("ok", false);
        writer.WriteStartObject("error");
        writer.WriteString("code", code);
        writer.WriteString("message", message);
        writer.WriteEndObject();
        writer.WriteEndObject();
    }
}

/// <summary>Routes a failure to stdout (JSON mode) or stderr (text mode).</summary>
public static class CliOutput
{
    /// <summary>
    /// Emits one failure and nothing else. In JSON mode the document goes to stdout so a caller
    /// can parse it; the human-readable line still goes to stderr so redirected runs leave a trace.
    /// </summary>
    public static void WriteError(TextWriter stdout, TextWriter stderr, bool json, string code, string message)
    {
        ArgumentNullException.ThrowIfNull(stdout);
        ArgumentNullException.ThrowIfNull(stderr);
        string line = "error " + code + ": " + message;
        if (json)
        {
            stdout.WriteLine(CliJson.Write(writer => CliJson.WriteError(writer, code, message)));
        }

        stderr.WriteLine(line);
    }
}