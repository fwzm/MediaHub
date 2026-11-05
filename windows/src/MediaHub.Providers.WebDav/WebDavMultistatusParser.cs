using System.Text;
using System.Xml;
using MediaHub.Domain;

namespace MediaHub.Providers.WebDav;

/// <summary>One <c>response</c> element of a DAV <c>multistatus</c> document (RFC 4918 §13).</summary>
/// <remarks>
/// <see cref="Href"/> keeps the server's exact string, undecoded: percent-decoding happens once, at
/// the point where the reference becomes a request target or a display name, never twice.
/// </remarks>
internal sealed record WebDavResource(
    string Href,
    bool IsCollection,
    string? DisplayName,
    long? ContentLength,
    string? ContentType,
    string? LastModified,
    string? ETag);

/// <summary>
/// Parser for the <c>207 Multi-Status</c> body of a PROPFIND response.
/// </summary>
/// <remarks>
/// <para>
/// Ported from the Android client's <c>WebDavMultistatusParser</c>, including its two hard-won
/// correctness rules: a property is only taken from a propstat whose own status line is 2xx, and the
/// resource-type <c>collection</c> element obeys that same rule — otherwise a failed propstat can
/// promote a file to a folder. Namespace comparison makes the prefix the server chose
/// (<c>D:</c>, <c>d:</c>, <c>lp1:</c>) irrelevant.
/// </para>
/// <para>
/// Security. The reader is created with <see cref="DtdProcessing.Prohibit"/> and a null
/// <see cref="XmlReaderSettings.XmlResolver"/>. Those two settings are the whole XXE defence, which
/// is why they live in a named factory the tests can assert on directly rather than being inlined:
/// a DOCTYPE is a protocol error here, so neither an external entity nor an entity-expansion bomb
/// ever reaches a resolver. <see cref="MaxCharactersInDocument"/> bounds the document as a second
/// line of defence behind the caller's own byte ceiling.
/// </para>
/// </remarks>
internal static class WebDavMultistatusParser
{
    /// <summary>Hard ceiling on a single directory response (8 MiB) — a directory can be huge or hostile.</summary>
    public const long MaxResponseBytes = 8L * 1024 * 1024;

    private const string DavNamespace = "DAV:";

    /// <summary>
    /// The XML reader configuration that makes XXE impossible.
    /// </summary>
    /// <remarks>
    /// Exposed so a test can pin the two settings that carry the security property; if either is
    /// relaxed, <c>Secure_reader_settings_prohibit_dtd_and_disable_the_resolver</c> fails before any
    /// entity test has a chance to pass accidentally.
    /// </remarks>
    internal static XmlReaderSettings CreateSecureReaderSettings() => new()
    {
        DtdProcessing = DtdProcessing.Prohibit,
        XmlResolver = null,
        IgnoreComments = true,
        IgnoreProcessingInstructions = true,
        IgnoreWhitespace = true,
        MaxCharactersFromEntities = 0,
        MaxCharactersInDocument = MaxResponseBytes,
        CloseInput = false,
        ConformanceLevel = ConformanceLevel.Document,
    };

    /// <summary>Parses a bounded multistatus body, or throws <see cref="ProviderException"/> Parse.</summary>
    public static IReadOnlyList<WebDavResource> Parse(byte[] xml, string serverId)
    {
        ArgumentNullException.ThrowIfNull(xml);
        try
        {
            using var stream = new MemoryStream(xml, writable: false);
            using XmlReader reader = XmlReader.Create(stream, CreateSecureReaderSettings());
            return Read(reader);
        }
        catch (XmlException ex)
        {
            // A DOCTYPE, an unresolved entity or malformed markup all land here. The cause is
            // sanitised by ProviderException, so no fragment of a hostile document reaches a log.
            throw ProviderException.Parse(serverId, ex);
        }
        catch (InvalidOperationException ex)
        {
            throw ProviderException.Parse(serverId, ex);
        }
    }

    private static List<WebDavResource> Read(XmlReader reader)
    {
        var handler = new Handler();
        while (reader.Read())
        {
            switch (reader.NodeType)
            {
                case XmlNodeType.Element:
                    if (reader.NamespaceURI != DavNamespace) break;
                    handler.StartElement(reader.LocalName);
                    break;
                case XmlNodeType.Text:
                case XmlNodeType.CDATA:
                case XmlNodeType.SignificantWhitespace:
                    handler.Text(reader.Value);
                    break;
                case XmlNodeType.EndElement:
                    if (reader.NamespaceURI != DavNamespace) break;
                    handler.EndElement(reader.LocalName);
                    break;
            }
        }
        return handler.Result;
    }

    /// <summary>
    /// State machine over the DAV elements we recognise.
    /// </summary>
    /// <remarks>
    /// Untracked elements are ignored, but their children are still visited, which is what lets a
    /// server add vendor extensions without breaking the parse.
    /// </remarks>
    private sealed class Handler
    {
        public List<WebDavResource> Result { get; } = new();

        private string? _href;
        private bool _isCollection;
        private bool _inPropstat;
        private bool _propstatOk;
        private bool _pendingCollection;
        private bool _responseStatusSeen;
        private bool _responseOk;
        private readonly Dictionary<string, string> _okProps = new(StringComparer.Ordinal);
        private readonly Dictionary<string, string> _pendingProps = new(StringComparer.Ordinal);
        private StringBuilder? _text;

        public void StartElement(string localName)
        {
            switch (localName)
            {
                case "response":
                    Reset();
                    break;
                case "propstat":
                    _inPropstat = true;
                    _propstatOk = false;
                    _pendingProps.Clear();
                    _pendingCollection = false;
                    break;
                case "status":
                    _text = new StringBuilder();
                    break;
                case "collection":
                    // Ownership matters: only a successful propstat may promote the resource.
                    if (_inPropstat) _pendingCollection = true;
                    break;
                case "href":
                case "displayname":
                case "getcontentlength":
                case "getcontenttype":
                case "getlastmodified":
                case "getetag":
                    _text = new StringBuilder();
                    break;
            }
        }

        public void Text(string value) => _text?.Append(value);

        public void EndElement(string localName)
        {
            string? value = _text?.ToString()?.Trim();
            switch (localName)
            {
                case "status":
                    if (_inPropstat)
                    {
                        _propstatOk = IsSuccess(value);
                    }
                    else
                    {
                        _responseStatusSeen = true;
                        _responseOk = IsSuccess(value);
                    }
                    _text = null;
                    break;

                case "propstat":
                    if (_propstatOk)
                    {
                        foreach (var pair in _pendingProps) _okProps[pair.Key] = pair.Value;
                        _isCollection |= _pendingCollection;
                    }
                    _pendingProps.Clear();
                    _pendingCollection = false;
                    _inPropstat = false;
                    _propstatOk = false;
                    break;

                case "href":
                    _href = value;
                    _text = null;
                    break;

                case "displayname":
                case "getcontentlength":
                case "getcontenttype":
                case "getlastmodified":
                case "getetag":
                    _text = null;
                    if (!_inPropstat || value is null) break;
                    _pendingProps[localName] = value;
                    break;

                case "response":
                    Emit();
                    Reset();
                    break;
            }
        }

        private void Emit()
        {
            // A response-level status (the non-propstat form of §9.1.2) is authoritative when present.
            if (_responseStatusSeen && !_responseOk) return;
            string? href = _href;
            if (string.IsNullOrWhiteSpace(href)) return;

            Result.Add(new WebDavResource(
                Href: href!,
                IsCollection: _isCollection,
                DisplayName: Trimmed(_okProps, "displayname"),
                ContentLength: _okProps.TryGetValue("getcontentlength", out string? raw) && long.TryParse(
                    raw, System.Globalization.NumberStyles.Integer, System.Globalization.CultureInfo.InvariantCulture, out long parsed)
                    ? parsed
                    : null,
                ContentType: Trimmed(_okProps, "getcontenttype"),
                LastModified: Trimmed(_okProps, "getlastmodified"),
                ETag: Trimmed(_okProps, "getetag")));
        }

        private static string? Trimmed(Dictionary<string, string> props, string key)
            => props.TryGetValue(key, out string? value) && value.Trim().Length > 0 ? value.Trim() : null;

        /// <summary><c>HTTP/1.1 207 Multi-Status</c> → 207. An unparseable status line counts as failure.</summary>
        private static bool IsSuccess(string? statusLine)
        {
            if (string.IsNullOrWhiteSpace(statusLine)) return false;
            string[] parts = statusLine.Trim().Split(' ', StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length < 2) return false;
            if (!parts[0].StartsWith("HTTP/", StringComparison.OrdinalIgnoreCase)) return false;
            if (!int.TryParse(parts[1], System.Globalization.NumberStyles.Integer,
                    System.Globalization.CultureInfo.InvariantCulture, out int code)) return false;
            return code is >= 200 and <= 299;
        }

        private void Reset()
        {
            _href = null;
            _isCollection = false;
            _inPropstat = false;
            _propstatOk = false;
            _pendingCollection = false;
            _responseStatusSeen = false;
            _responseOk = false;
            _okProps.Clear();
            _pendingProps.Clear();
            _text = null;
        }
    }
}
