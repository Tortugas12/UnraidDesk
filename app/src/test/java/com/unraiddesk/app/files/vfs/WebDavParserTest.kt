package com.unraiddesk.app.files.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavParserTest {

    @Test
    fun parsesPropfindXmlResponseAndSkipsSelfDirectoryEntry() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/webdav/media/</D:href>
                <D:propstat>
                  <D:prop>
                    <D:resourcetype><D:collection/></D:resourcetype>
                  </D:prop>
                </D:propstat>
              </D:response>
              <D:response>
                <D:href>/webdav/media/Movies/</D:href>
                <D:propstat>
                  <D:prop>
                    <D:displayname>Movies</D:displayname>
                    <D:resourcetype><D:collection/></D:resourcetype>
                  </D:prop>
                </D:propstat>
              </D:response>
              <D:response>
                <D:href>/webdav/media/demo%20clip.mp4</D:href>
                <D:propstat>
                  <D:prop>
                    <D:getcontentlength>1048576</D:getcontentlength>
                    <D:getcontenttype>video/mp4</D:getcontenttype>
                    <D:getlastmodified>Wed, 25 Nov 2026 10:00:00 GMT</D:getlastmodified>
                    <D:resourcetype/>
                  </D:prop>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val entries = WebDavFileSystem.parsePropfindResponse(xml, "media")
        assertEquals(2, entries.size)

        val movies = entries[0]
        assertEquals("Movies", movies.name)
        assertEquals("media/Movies", movies.path)
        assertTrue(movies.isDirectory)

        val video = entries[1]
        assertEquals("demo clip.mp4", video.name)
        assertEquals("media/demo clip.mp4", video.path)
        assertFalse(video.isDirectory)
        assertEquals(1048576L, video.sizeBytes)
        assertEquals("video/mp4", video.mimeType)
    }
}
