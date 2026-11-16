using MediaHub.Domain;
using MediaHub.Net;

namespace MediaHub.Providers.Http;

/// <summary>
/// Builds the handle for a plain http(s) address or m3u list. The address is the whole configuration:
/// there is no login step and therefore no credential material to keep anywhere.
/// </summary>
public sealed class HttpSourceProviderFactory : IMediaProviderFactory
{
    public ProviderDescriptor Descriptor { get; } = new(
        ServerType.Http,
        "HTTP 直链 / 播放列表",
        "单个 http(s) 媒体文件，或 m3u/m3u8 播放列表；无需账号。",
        ProviderCapability.Browse | ProviderCapability.Playback | ProviderCapability.Progress);

    public ProviderHandle Create(MediaServer server, NetworkPolicy policy, IReadOnlyDictionary<string, string>? credentials = null)
    {
        string address = server.PrimaryEndpoint?.Url ?? "";
        if (!Uri.TryCreate(address, UriKind.Absolute, out Uri? source) || source.Scheme is not ("http" or "https"))
        {
            throw ProviderException.Connection(server.ServerId, "该直链来源的地址无效，请重新添加");
        }

        var clients = new MediaSourceHttpClientFactory(policy);
        var provider = new HttpSourceProvider(server.ServerId, source, clients.CreateApiClient());
        var handle = new ProviderHandle
        {
            ServerId = server.ServerId,
            Server = server,
            Descriptor = Descriptor,
            Browse = provider,
            Playback = provider,
            Progress = provider,
        };
        handle.Own(clients);
        return handle;
    }
}
