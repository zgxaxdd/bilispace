package com.naaammme.bbspace.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareUtilsTest {

    @Test
    fun `videoUrl prefers bvid`() {
        assertEquals(
            "https://www.bilibili.com/video/BV1xx411c7mD/",
            ShareUtils.videoUrl("BV1xx411c7mD", aid = 123L)
        )
    }

    @Test
    fun `videoUrl falls back to av link without bvid`() {
        assertEquals(
            "https://www.bilibili.com/av123/",
            ShareUtils.videoUrl(bvid = null, aid = 123L)
        )
    }

    @Test
    fun `videoUrl appends page param only beyond P1`() {
        assertEquals(
            "https://www.bilibili.com/video/BV1xx411c7mD/?p=2",
            ShareUtils.videoUrl("BV1xx411c7mD", aid = 123L, page = 2)
        )
        assertEquals(
            "https://www.bilibili.com/video/BV1xx411c7mD/",
            ShareUtils.videoUrl("BV1xx411c7mD", aid = 123L, page = 1)
        )
    }

    @Test
    fun `dynamicUrl builds opus link`() {
        assertEquals(
            "https://www.bilibili.com/opus/987654321",
            ShareUtils.dynamicUrl("987654321")
        )
    }

    @Test
    fun `pgcUrl builds bangumi episode link`() {
        assertEquals(
            "https://www.bilibili.com/bangumi/play/ep12345/",
            ShareUtils.pgcUrl(12345L)
        )
    }

    @Test
    fun `commentUrl uses question mark without page param`() {
        assertEquals(
            "https://www.bilibili.com/video/BV1xx411c7mD/?comment_root_id=42",
            ShareUtils.commentUrl("BV1xx411c7mD", aid = 123L, rpid = 42L)
        )
    }

    @Test
    fun `commentUrl uses ampersand with page param`() {
        assertEquals(
            "https://www.bilibili.com/video/BV1xx411c7mD/?p=3&comment_root_id=42",
            ShareUtils.commentUrl("BV1xx411c7mD", aid = 123L, rpid = 42L, page = 3)
        )
    }
}
