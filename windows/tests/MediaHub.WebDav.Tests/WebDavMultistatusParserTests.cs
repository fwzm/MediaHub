using System.Text;
using System.Xml;
using MediaHub.Domain;
using MediaHub.Providers.WebDav;
using Xunit;

namespace MediaHub.WebDav.Tests;

/// <summary>
/// XXE and multistatus correctness tests.
/// </summary>
/// <remarks>
/// The DOCTYPE tests are the acceptance criterion for "XML external entity must be impossible". They
/// assert three things at once: the document is refused, the refusal is a Parse failure rather than a
/// silent empty result, and no request ever reached the address the entity pointed at. The last one is
/// what separates "we rejected it" from "we resolved it and discarded the value".
/// </remarks>
public sealed class WebDavMultistatusParserTests
{
    private const string ServerId = "wdav-test";

    private static IReadOnlyList<WebDavResource> Parse(string xml)
        => WebDavMultistatusParser.Parse(Encoding.UTF8.GetBytes(xml), ServerId);

    private static ProviderException ParseFailure(string xml)
        => Assert.Throws<ProviderException>(() => Parse(xml));

    private static string SingleFile(string href, long? length = null, string? contentType = null)
        => "<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>" + href + "</D:href>" +
           "<D:propstat><D:prop><D:resourcetype/>" +
           (length is { } l ? "<D:getcontentlength>" + l + "</D:getcontentlength>" : "") +
           (contentType is not null ? "<D:getcontenttype>" + contentType + "</D:getcontenttype>" : "") +
           "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>";

    // ---------------------------------------------------------------- XXE

    [Fact]
    public void Secure_reader_settings_prohibit_dtd_and_disable_the_resolver()
    {
        // The first line of defence, asserted directly: if DtdProcessing is relaxed, this test fails
        // before the DOCTYPE tests below can pass for the wrong reason.
        XmlReaderSettings settings = WebDavMultistatusParser.CreateSecureReaderSettings();

        Assert.Equal(DtdProcessing.Prohibit, settings.DtdProcessing);
        Assert.Equal(0, settings.MaxCharactersFromEntities);
        Assert.Equal(WebDavMultistatusParser.MaxResponseBytes, settings.MaxCharactersInDocument);

        // XmlResolver is write-only on XmlReaderSettings, so the factory's "= null" cannot be read
        // back. Its effect is proven behaviourally instead: with a resolver installed, the DOCTYPE
        // tests below would fetch the entity and the loopback canary would receive a request.
    }

    [Fact]
    public async Task Doctype_with_an_external_entity_is_rejected_and_never_resolved()
    {
        await using WebDavTestServer canary = await WebDavTestServer.StartAsync();
        string entityUrl = "http://127.0.0.1:" + canary.Port + "/xxe-canary";

        string xml = "<?xml version=\"1.0\"?>" +
                     "<!DOCTYPE D:multistatus [ <!ENTITY xxe SYSTEM \"" + entityUrl + "\"> ]>" +
                     "<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>/dav/&xxe;</D:href></D:response></D:multistatus>";

        ProviderException failure = ParseFailure(xml);

        Assert.Equal(ProviderErrorCode.Parse, failure.Code);
        // Nothing was fetched: a resolver that had been invoked would have produced a request here.
        Assert.Empty(canary.Requests);
    }

    [Fact]
    public void Doctype_pointing_at_a_local_file_is_rejected_without_reading_it()
    {
        string xml = "<?xml version=\"1.0\"?>" +
                     "<!DOCTYPE D:multistatus [ <!ENTITY xxe SYSTEM \"file:///C:/Windows/win.ini\"> ]>" +
                     "<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>/dav/&xxe;</D:href></D:response></D:multistatus>";

        ProviderException failure = ParseFailure(xml);

        Assert.Equal(ProviderErrorCode.Parse, failure.Code);
        // The failure message is fixed and carries no fragment of the document, so nothing about the
        // host or the file it referenced can leak into a log through the error path.
        Assert.DoesNotContain("win.ini", failure.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void Empty_document_is_rejected_as_a_parse_error()
    {
        Assert.Equal(ProviderErrorCode.Parse, ParseFailure("").Code);
    }

    [Fact]
    public void External_parameter_entity_is_rejected()
    {
        string xml = "<?xml version=\"1.0\"?>" +
                     "<!DOCTYPE D:multistatus [ <!ENTITY % remote SYSTEM \"http://127.0.0.1:1/x.dtd\"> %remote; ]>" +
                     "<D:multistatus xmlns:D=\"DAV:\"/>";

        Assert.Equal(ProviderErrorCode.Parse, ParseFailure(xml).Code);
    }

    [Fact]
    public void Internal_entity_expansion_is_rejected()
    {
        // Billion laughs: with DTDs prohibited the expansion never gets a chance to start.
        string xml = "<?xml version=\"1.0\"?>" +
                     "<!DOCTYPE lolz [ <!ENTITY lol \"lol\"> <!ENTITY lol2 \"&lol;&lol;&lol;&lol;&lol;\"> ]>" +
                     "<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>&lol2;</D:href></D:response></D:multistatus>";

        Assert.Equal(ProviderErrorCode.Parse, ParseFailure(xml).Code);
    }

    [Fact]
    public void Hostile_document_never_leaks_its_content_through_the_error_message()
    {
        string xml = "<!DOCTYPE D:multistatus [ <!ENTITY xxe SYSTEM \"http://secret-host.invalid/leak\"> ]>" +
                     "<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>&xxe;</D:href></D:response></D:multistatus>";

        ProviderException failure = ParseFailure(xml);

        Assert.Equal("数据解析失败", failure.Message);
        Assert.DoesNotContain("secret-host", failure.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void A_document_without_a_doctype_still_parses()
    {
        // The guard must reject DTDs, not XML.
        IReadOnlyList<WebDavResource> resources = Parse(SingleFile("/dav/movie.mkv", 42, "video/x-matroska"));

        WebDavResource resource = Assert.Single(resources);
        Assert.Equal("/dav/movie.mkv", resource.Href);
        Assert.Equal(42, resource.ContentLength);
        Assert.Equal("video/x-matroska", resource.ContentType);
    }

    // ---------------------------------------------------------------- multistatus semantics

    [Theory]
    [InlineData("D")]
    [InlineData("d")]
    [InlineData("lp1")]
    public void Namespace_prefix_is_irrelevant(string prefix)
    {
        string xml = "<" + prefix + ":multistatus xmlns:" + prefix + "=\"DAV:\">" +
                     "<" + prefix + ":response><" + prefix + ":href>/dav/a.mkv</" + prefix + ":href>" +
                     "<" + prefix + ":propstat><" + prefix + ":prop><" + prefix + ":resourcetype/>" +
                     "</" + prefix + ":prop><" + prefix + ":status>HTTP/1.1 200 OK</" + prefix + ":status>" +
                     "</" + prefix + ":propstat></" + prefix + ":response></" + prefix + ":multistatus>";

        Assert.Single(Parse(xml));
    }

    [Fact]
    public void Collection_is_only_taken_from_a_successful_propstat()
    {
        // A failed propstat must not promote a file to a folder; a folder-shaped 404 is still a file.
        string xml = "<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>/dav/x.mkv</D:href>" +
                     "<D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>" +
                     "<D:status>HTTP/1.1 404 Not Found</D:status></D:propstat>" +
                     "<D:propstat><D:prop><D:getcontentlength>10</D:getcontentlength></D:prop>" +
                     "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>";

        WebDavResource resource = Assert.Single(Parse(xml));

        Assert.False(resource.IsCollection);
        Assert.Equal(10, resource.ContentLength);
    }

    [Fact]
    public void Collection_from_a_successful_propstat_marks_the_resource_as_a_folder()
    {
        string xml = "<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>/dav/sub/</D:href>" +
                     "<D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>" +
                     "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>";

        Assert.True(Assert.Single(Parse(xml)).IsCollection);
    }

    [Fact]
    public void Response_level_failure_drops_the_entry()
    {
        string xml = "<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>/dav/gone.mkv</D:href>" +
                     "<D:status>HTTP/1.1 404 Not Found</D:status></D:response></D:multistatus>";

        Assert.Empty(Parse(xml));
    }

    [Fact]
    public void Response_without_a_propstat_still_yields_its_href()
    {
        // The entry exists even when no property is trustworthy; the caller decides what to do with it.
        string xml = "<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>/dav/plain.mkv</D:href></D:response></D:multistatus>";

        WebDavResource resource = Assert.Single(Parse(xml));

        Assert.Equal("/dav/plain.mkv", resource.Href);
        Assert.Null(resource.ContentLength);
        Assert.Null(resource.ContentType);
    }

    [Fact]
    public void Content_length_is_ignored_when_it_is_not_a_number()
    {
        string xml = SingleFile("/dav/x.mkv", length: null);
        xml = xml.Replace("<D:resourcetype/>", "<D:resourcetype/><D:getcontentlength>not-a-number</D:getcontentlength>", StringComparison.Ordinal);

        Assert.Null(Assert.Single(Parse(xml)).ContentLength);
    }

    [Fact]
    public void Multistatus_with_no_responses_parses_to_an_empty_list()
    {
        Assert.Empty(Parse("<D:multistatus xmlns:D=\"DAV:\"/>"));
    }

    [Fact]
    public void Malformed_xml_is_rejected_as_a_parse_error()
    {
        Assert.Equal(ProviderErrorCode.Parse, ParseFailure("<D:multistatus xmlns:D=\"DAV:\"><D:response>").Code);
    }

    [Fact]
    public void Entries_without_an_href_are_dropped()
    {
        string xml = "<D:multistatus xmlns:D=\"DAV:\"><D:response><D:propstat><D:prop><D:resourcetype/></D:prop>" +
                     "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>";

        Assert.Empty(Parse(xml));
    }
}
