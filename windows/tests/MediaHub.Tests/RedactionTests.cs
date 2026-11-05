using MediaHub.Net;
using Xunit;

namespace MediaHub.Tests;

/// <summary>
/// Redaction is a security control, not a formatting convenience: diagnostics are useless if they
/// cannot be shared, and dangerous if sharing them leaks a token. These tests pin the cases that
/// actually occur in this client.
/// </summary>
public sealed class RedactionTests
{
    [Theory]
    [InlineData("Authorization: Bearer abc123def456", "abc123def456")]
    [InlineData("X-Emby-Token: 9f8e7d6c5b4a", "9f8e7d6c5b4a")]
    [InlineData("X-MediaBrowser-Token: deadbeefcafe", "deadbeefcafe")]
    [InlineData("Cookie: sessionid=abcdef123456", "abcdef123456")]
    [InlineData("Proxy-Authorization: Basic dXNlcjpwYXNz", "dXNlcjpwYXNz")]
    public void Header_values_are_masked(string input, string secret)
    {
        string redacted = LogService.Redact(input);
        Assert.DoesNotContain(secret, redacted);
        Assert.Contains("***", redacted);
    }

    [Fact]
    public void Header_name_is_preserved_so_the_log_stays_diagnostic()
    {
        string redacted = LogService.Redact("Authorization: Bearer abc123");
        Assert.Contains("Authorization", redacted);
    }

    [Theory]
    [InlineData("https://media.example.com/Items?api_key=SECRET123", "SECRET123")]
    [InlineData("https://media.example.com/Videos/1/stream?static=true&PlaySessionId=PSID999", "PSID999")]
    [InlineData("https://media.example.com/x?access_token=TOK456", "TOK456")]
    [InlineData("https://media.example.com/x?password=hunter2", "hunter2")]
    [InlineData("https://media.example.com/x?token=abc", "abc")]
    public void Credential_query_values_are_masked(string input, string secret)
    {
        string redacted = LogService.Redact(input);
        Assert.DoesNotContain(secret, redacted);
    }

    [Fact]
    public void Non_credential_query_parameters_survive()
    {
        string redacted = LogService.Redact("https://media.example.com/Items?ParentId=abc&Limit=50");
        Assert.Contains("ParentId=abc", redacted);
        Assert.Contains("Limit=50", redacted);
    }

    [Fact]
    public void Url_user_info_is_masked()
    {
        string redacted = LogService.Redact("https://user:hunter2@media.example.com/path");
        Assert.DoesNotContain("hunter2", redacted);
    }

    [Fact]
    public void Jwt_shaped_tokens_are_masked_even_without_a_key()
    {
        string jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVP";
        string redacted = LogService.Redact("token was " + jwt);
        Assert.DoesNotContain(jwt, redacted);
    }

    [Fact]
    public void Ordinary_text_is_left_alone()
    {
        const string text = "播放失败：编解码器不受支持（H.265 Main10）";
        Assert.Equal(text, LogService.Redact(text));
    }

    [Fact]
    public void Null_and_empty_are_safe()
    {
        Assert.Equal("", LogService.Redact(null));
        Assert.Equal("", LogService.Redact(""));
    }

    [Fact]
    public void Exported_report_contains_no_credentials()
    {
        var log = new LogService();
        log.Info("auth", "登录 https://media.example.com/Users/AuthenticateByName?api_key=SECRET123");
        log.Error("player", "请求失败", new InvalidOperationException("Authorization: Bearer SECRET456"));

        string exported = log.Export();
        Assert.DoesNotContain("SECRET123", exported);
        Assert.DoesNotContain("SECRET456", exported);
    }
}
