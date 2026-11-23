package com.example

import com.example.data.NHCService
import com.example.data.NHCTrackParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NHCTrackParserTest {

    // Condensed from a real EP032026 (Cristina) TRACK KMZ, advisory 11A, June 2026.
    private val sampleKml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <kml xmlns="http://www.opengis.net/kml/2.2">
        <Document>
        <Folder id="Forecast Track">
           <Placemark>
              <description><![CDATA[<table>
                    <tr><td>72 Hour Forecast Track</td></tr>]]></description>
              <styleUrl>#72_line</styleUrl>
              <LineString>
                <coordinates>  -89.1,12.8,0 -89.5,13.2,0 -90.0,14.6,0 </coordinates>
              </LineString>
           </Placemark>
           <Placemark>
              <description><![CDATA[<table>
                    <tr><td nowrap>Advisory Information</td></tr>
                    <tr><td nowrap>Valid at:  6:00 PM CST June 10, 2026 </td></tr>
                    <tr><td nowrap>Location: 12.8 N, -89.1 W </td></tr>
                    <tr><td nowrap>Maximum Wind: 30 knots (35 mph) </td></tr>
                    <tr><td nowrap>Minimum Pressure: 1007 mb </td></tr>
              ]]></description>
              <styleUrl>#initial_point</styleUrl>
              <Point>
                 <coordinates> -89.1,12.8,0 </coordinates>
              </Point>
           </Placemark>
           <Placemark>
              <description><![CDATA[<table>
                    <tr><td nowrap>12 hr Forecast</td></tr>
                    <tr><td nowrap>Valid at: 12:00 AM CST June 11, 2026 </td></tr>
                    <tr><td nowrap>Maximum Wind: 30 knots (35 mph) </td></tr>
              ]]></description>
              <styleUrl>#d_point</styleUrl>
              <Point>
                 <coordinates> -89.5,13.2,0 </coordinates>
              </Point>
           </Placemark>
           <Placemark>
              <description><![CDATA[<table>
                    <tr><td nowrap>24 hr Forecast</td></tr>
                    <tr><td nowrap>Valid at: 12:00 PM CST June 11, 2026 </td></tr>
                    <tr><td nowrap>Maximum Wind: 25 knots (30 mph) </td></tr>
              ]]></description>
              <styleUrl>#xd_point</styleUrl>
              <Point>
                 <coordinates> -90.0,14.6,0 </coordinates>
              </Point>
           </Placemark>
        </Folder>
        </Document>
        </kml>
    """.trimIndent()

    @Test
    fun `parses point placemarks and skips line strings`() {
        val points = NHCTrackParser.parseTrackKml(sampleKml)
        assertEquals(3, points.size)
    }

    @Test
    fun `initial point carries position winds and pressure`() {
        val initial = NHCTrackParser.parseTrackKml(sampleKml).first()
        assertFalse(initial.isForecast)
        assertEquals("Current Center", initial.timeLabel)
        assertEquals(12.8, initial.latitude, 1e-9)
        assertEquals(-89.1, initial.longitude, 1e-9)
        assertEquals(30, initial.windSpeedKt)
        assertEquals(1007, initial.pressureMb)
        assertEquals("6:00 PM CST June 10, 2026", initial.dateText)
    }

    @Test
    fun `forecast points are labeled by hour`() {
        val points = NHCTrackParser.parseTrackKml(sampleKml)
        val f12 = points[1]
        val f24 = points[2]
        assertTrue(f12.isForecast)
        assertEquals("12h Forecast", f12.timeLabel)
        assertEquals(30, f12.windSpeedKt)
        assertEquals("24h Forecast", f24.timeLabel)
        assertEquals(25, f24.windSpeedKt)
        assertEquals(14.6, f24.latitude, 1e-9)
        assertEquals(-90.0, f24.longitude, 1e-9)
    }

    @Test
    fun `advisory numbers normalize to text product form`() {
        assertEquals("11A", NHCService.normalizeAdvNum("011a"))
        assertEquals("3", NHCService.normalizeAdvNum("003"))
        assertNull(NHCService.normalizeAdvNum(null))
        assertNull(NHCService.normalizeAdvNum("000"))
    }

    @Test
    fun `Atlantic activity filter excludes Pacific storm ids`() {
        assertTrue(NHCService.isAtlanticStorm("AL032026"))
        assertTrue(NHCService.isAtlanticStorm("al042026"))
        assertFalse(NHCService.isAtlanticStorm("EP032026"))
        assertFalse(NHCService.isAtlanticStorm("CP012026"))
        assertFalse(NHCService.isAtlanticStorm(""))
    }
}
