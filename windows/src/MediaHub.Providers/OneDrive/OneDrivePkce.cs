using System.Security.Cryptography;
using System.Text;

namespace MediaHub.Providers.OneDrive;

/// <summary>
/// Proof key for the authorization-code exchange.
/// </summary>
/// <remarks>
/// A desktop app cannot keep a client secret, so the code it receives on the loopback callback would
/// be enough for anyone who intercepted it. PKCE binds the code to a per-attempt random value that
/// never leaves this process: the authorization request carries only its SHA-256 hash, and the token
/// request has to present the original. The state value is the second half of the same defence
/// against a callback that was not asked for.
/// </remarks>
internal sealed record PkceChallenge(string Verifier, string Challenge, string State)
{
    /// <summary>Length in characters; RFC 7636 allows 43 to 128, and 64 random bytes land at 86.</summary>
    public const int VerifierBytes = 64;

    public static PkceChallenge Create()
    {
        byte[] verifierBytes = RandomNumberGenerator.GetBytes(VerifierBytes);
        string verifier = Base64Url(verifierBytes);
        string challenge = Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)));
        string state = Base64Url(RandomNumberGenerator.GetBytes(32));
        return new PkceChallenge(verifier, challenge, state);
    }

    /// <summary>Builds the browser address; the URI is assembled by hand because of the fragment-free query.</summary>
    public Uri AuthorizeUri(OneDriveConfig config)
    {
        var query = new StringBuilder();
        Append(query, "client_id", config.ClientId);
        Append(query, "response_type", "code");
        Append(query, "redirect_uri", config.RedirectUri);
        Append(query, "response_mode", "query");
        Append(query, "scope", config.Scope);
        Append(query, "state", State);
        Append(query, "code_challenge", Challenge);
        Append(query, "code_challenge_method", "S256");
        return new Uri(config.AuthorizeEndpoint + "?" + query);
    }

    /// <summary>
    /// Base64url without padding, as required for a verifier and for a challenge.
    /// </summary>
    public static string Base64Url(byte[] bytes)
        => Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static void Append(StringBuilder query, string name, string value)
    {
        if (query.Length > 0) query.Append('&');
        query.Append(name).Append('=').Append(Uri.EscapeDataString(value));
    }
}
