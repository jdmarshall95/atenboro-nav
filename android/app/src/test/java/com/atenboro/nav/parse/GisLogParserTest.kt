package com.atenboro.nav.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GisLogParserTest {

    @Test
    fun parsesVoiceClipRightOver400() {
        val line =
            "[DomainSynthesizerPlayer] At 0 going to play 3989ms of " +
                "'CrossroadsTurnDirectionRightThenCrossroadsTurnDirectionRightOver400' " +
                "High priority clip for start instruction from 377"
        val h = GisLogParser.parseLine(line)
        assertNotNull(h)
        assertEquals("right", h!!.turn)
        assertEquals(400, h.distM)
    }

    @Test
    fun parsesRemainingLength() {
        val line =
            "[CoreTransportRouting] Updating remaining length for route id foo to 13270 meters"
        assertEquals(13270, GisLogParser.parseRouteRemain(line))
    }

    @Test
    fun decodeSlightLeft() {
        val (turn, dist) = GisLogParser.decodeClip("CrossroadsTurnDirectionSlightlyLeftOver200")
        assertEquals("slight_left", turn)
        assertEquals(200, dist)
    }
}
