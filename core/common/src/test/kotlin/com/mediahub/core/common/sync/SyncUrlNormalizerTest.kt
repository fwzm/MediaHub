package com.mediahub.core.common.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncUrlNormalizerTest {

    @Test
    fun http_url_gets_an_explicit_port_and_no_trailing_slash() {
        assertEquals("http://nas-a:8096/emby", SyncUrlNormalizer.normalize("http://nas-a:8096/emby/"))
        assertEquals("http://nas-a:8096", SyncUrlNormalizer.normalize("http://nas-a:8096"))
    }

    @Test
    fun the_scheme_default_port_is_made_explicit() {
        assertEquals("https://dav.example:443/gate", SyncUrlNormalizer.normalize("https://dav.example/gate"))
        assertEquals("http://host:80", SyncUrlNormalizer.normalize("http://host"))
    }

    @Test
    fun scheme_and_host_are_lowercased_but_the_path_is_not() {
        assertEquals("http://nas:8096/Emby", SyncUrlNormalizer.normalize("HTTP://NAS:8096/Emby"))
    }

    @Test
    fun repeated_trailing_slashes_collapse_to_no_slash() {
        assertEquals("http://h:8080/a", SyncUrlNormalizer.normalize("http://h:8080/a///"))
        assertEquals("http://h:8080", SyncUrlNormalizer.normalize("http://h:8080/"))
    }

    @Test
    fun query_and_fragment_are_dropped_because_they_are_not_identity() {
        assertEquals("https://nas:8920/emby", SyncUrlNormalizer.normalize("https://nas:8920/emby?api_key=abc&x=1"))
        assertEquals("https://nas:8920/emby", SyncUrlNormalizer.normalize("https://nas:8920/emby#frag"))
    }

    @Test
    fun user_info_is_stripped_and_never_echoed() {
        val normalized = SyncUrlNormalizer.normalize("https://alice:hunter2@nas:8920/emby")
        assertEquals("https://nas:8920/emby", normalized)
        assertFalse(normalized!!.contains("hunter2"))
        assertFalse(normalized.contains("alice"))
    }

    @Test
    fun scheme_relative_values_are_read_as_http() {
        assertEquals("http://nas:80/share", SyncUrlNormalizer.normalize("//nas/share"))
    }

    @Test
    fun a_bare_host_is_read_as_http_so_paste_forms_agree() {
        assertEquals("http://nas:8096/emby", SyncUrlNormalizer.normalize("nas:8096/emby"))
    }

    @Test
    fun smb_has_no_default_port_so_none_is_invented() {
        assertEquals("smb://nas/share", SyncUrlNormalizer.normalize("smb://nas/share/"))
        assertEquals("smb://nas:445/share", SyncUrlNormalizer.normalize("smb://nas:445/share"))
    }

    @Test
    fun file_urls_are_accepted_without_a_host() {
        assertEquals("file:///C:/media", SyncUrlNormalizer.normalize("file:///C:/media/"))
    }

    @Test
    fun unsupported_schemes_are_rejected_instead_of_guessed() {
        assertNull(SyncUrlNormalizer.normalize("ftp://host/x"))
        assertNull(SyncUrlNormalizer.normalize("mailto:alice@example.com"))
    }

    @Test
    fun a_value_that_embeds_credentials_without_a_scheme_is_rejected_not_reinterpreted() {
        assertNull(SyncUrlNormalizer.normalize("alice:hunter2@nas:8096/emby"))
    }

    @Test
    fun blank_and_null_mean_no_dialable_address() {
        assertNull(SyncUrlNormalizer.normalize(null))
        assertNull(SyncUrlNormalizer.normalize(""))
        assertNull(SyncUrlNormalizer.normalize("   "))
    }

    @Test
    fun normalisation_is_idempotent() {
        val inputs = listOf(
            "http://nas-a:8096/emby/",
            "https://dav.example/gate",
            "HTTP://NAS:8096/Emby",
            "smb://nas/share/",
            "//nas/share",
        )
        for (input in inputs) {
            val once = SyncUrlNormalizer.normalize(input)
            assertEquals(input, once, SyncUrlNormalizer.normalize(once))
        }
    }

    @Test
    fun try_normalize_reports_both_parts() {
        val ok = SyncUrlNormalizer.tryNormalize("http://nas:8096/emby")
        assertTrue(ok.first)
        assertEquals("http://nas:8096/emby", ok.second)

        val failed = SyncUrlNormalizer.tryNormalize("ftp://host/x")
        assertFalse(failed.first)
        assertNull(failed.second)
    }
}
