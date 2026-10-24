using MediaHub.Domain;
using MediaHub.Providers.Local;

namespace MediaHub.Providers.WebDav;

/// <summary>Extension and content-type recognition for WebDAV entries.</summary>
/// <remarks>
/// <para>
/// The video and subtitle extension sets are the <i>same objects</i> the local provider uses, not
/// copies: a file that shows up as a video in the local browser must not disappear when the same file
/// is reached over WebDAV. Duplicating the lists is how two providers drift apart, and the drift only
/// shows up as "the NAS folder is empty" long after the commit.
/// </para>
/// <para>
/// Language recognition follows the Android client's <c>SubtitleFormats.languageFromFileName</c>
/// instead: only a known language tag is reported and anything else stays unknown. The local
/// provider's more permissive "any 2–20 character suffix" rule turns a release-group tag into a
/// language, which is worse than admitting we do not know.
/// </para>
/// </remarks>
internal static class WebDavMediaTypes
{
    public static IReadOnlyCollection<string> VideoExtensions => LocalMediaProviderFactory.SupportedVideoExtensions;

    public static IReadOnlyCollection<string> SubtitleExtensions => LocalMediaProviderFactory.SupportedSubtitleExtensions;

    public static readonly HashSet<string> AudioExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".mp3", ".flac", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".wma", ".ape", ".alac", ".dsf", ".dff",
    };

    /// <summary>Container types that are video without carrying a <c>video/</c> prefix.</summary>
    private static readonly HashSet<string> VideoContentTypes = new(StringComparer.OrdinalIgnoreCase)
    {
        "application/x-matroska", "application/x-msvideo", "application/mp4",
        "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/vnd.rn-realvideo",
    };

    /// <summary>Extension of a file name, lower-case, including the dot; empty when there is none.</summary>
    public static string ExtensionOf(string? name)
        => string.IsNullOrEmpty(name) ? string.Empty : Path.GetExtension(name).ToLowerInvariant();

    public static bool IsVideoExtension(string? name) => VideoExtensions.Contains(ExtensionOf(name));

    public static bool IsAudioExtension(string? name) => AudioExtensions.Contains(ExtensionOf(name));

    public static bool IsSubtitleExtension(string? name) => SubtitleExtensions.Contains(ExtensionOf(name));

    /// <summary>Container name (extension without the dot, lower-case), or null when there is none.</summary>
    public static string? ContainerOf(string? name)
    {
        string extension = ExtensionOf(name);
        return extension.Length > 1 ? extension[1..] : null;
    }

    /// <summary>True when the reported content type is a video type.</summary>
    public static bool IsVideoContentType(string? contentType)
    {
        string value = Normalize(contentType);
        return value.StartsWith("video/", StringComparison.Ordinal) || VideoContentTypes.Contains(value);
    }

    public static bool IsAudioContentType(string? contentType)
    {
        string value = Normalize(contentType);
        return value.StartsWith("audio/", StringComparison.Ordinal) || value == "application/ogg";
    }

    /// <summary>
    /// Classifies a directory entry from its name and, when the server reports one, its content type.
    /// </summary>
    /// <remarks>
    /// Either signal is sufficient: a server that reports <c>video/x-matroska</c> for an extensionless
    /// file is believed, and a server that reports <c>application/octet-stream</c> for <c>a.mkv</c>
    /// does not erase what the name says. Nothing is fabricated beyond the two signals — an unknown
    /// name with an unknown type stays <see cref="MediaType.Other"/>.
    /// </remarks>
    public static MediaType Classify(string? name, string? contentType, bool isCollection)
    {
        if (isCollection) return MediaType.Folder;
        if (IsVideoExtension(name) || IsVideoContentType(contentType)) return MediaType.Video;
        if (IsAudioExtension(name) || IsAudioContentType(contentType)) return MediaType.Audio;
        return MediaType.Other;
    }

    /// <summary>
    /// Language tag from a file name suffix, normalized, or null when it is not a language we know.
    /// </summary>
    /// <remarks>Port of Android <c>SubtitleFormats.languageFromFileName</c>; see the type remarks.</remarks>
    public static string? LanguageFromFileName(string? fileName)
    {
        if (string.IsNullOrWhiteSpace(fileName)) return null;

        int lastDot = fileName.LastIndexOf('.');
        string stem = lastDot > 0 ? fileName[..lastDot] : fileName;
        int suffixDot = stem.LastIndexOf('.');
        string tag = (suffixDot >= 0 ? stem[(suffixDot + 1)..] : string.Empty).ToLowerInvariant();

        if (tag.Length == 0 || tag.Length > 5 || tag.Contains(' ')) return null;
        return KnownLanguageTags.TryGetValue(tag, out string? normalized) ? normalized : null;
    }

    private static string Normalize(string? contentType)
    {
        if (string.IsNullOrWhiteSpace(contentType)) return string.Empty;
        int semicolon = contentType.IndexOf(';');
        return (semicolon >= 0 ? contentType[..semicolon] : contentType).Trim().ToLowerInvariant();
    }

    /// <summary>Language codes we accept, normalized BCP-47 style (port of the Android table).</summary>
    private static readonly Dictionary<string, string> KnownLanguageTags = new(StringComparer.OrdinalIgnoreCase)
    {
        ["zh"] = "zh", ["zho"] = "zh", ["chs"] = "zh", ["sc"] = "zh", ["gb"] = "zh",
        ["zh-hans"] = "zh-cn", ["zh-cn"] = "zh-cn", ["zh-sg"] = "zh-cn",
        ["zh-hant"] = "zh-tw", ["zh-tw"] = "zh-tw", ["zh-hk"] = "zh-tw",
        ["cht"] = "zh-tw", ["tc"] = "zh-tw", ["big5"] = "zh-tw",
        ["en"] = "en", ["eng"] = "en",
        ["ja"] = "ja", ["jpn"] = "ja", ["jp"] = "ja",
        ["ko"] = "ko", ["kor"] = "ko", ["kr"] = "ko",
        ["fr"] = "fr", ["fra"] = "fr", ["fre"] = "fr",
        ["de"] = "de", ["deu"] = "de", ["ger"] = "de",
        ["es"] = "es", ["spa"] = "es",
        ["ru"] = "ru", ["rus"] = "ru",
        ["pt"] = "pt", ["por"] = "pt",
        ["it"] = "it", ["ita"] = "it",
        ["th"] = "th", ["tha"] = "th",
        ["vi"] = "vi", ["vie"] = "vi",
        ["id"] = "id", ["ind"] = "id",
        ["ar"] = "ar", ["ara"] = "ar",
        ["hi"] = "hi", ["hin"] = "hi",
    };
}
