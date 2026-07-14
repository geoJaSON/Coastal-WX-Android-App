package com.example

import com.example.data.NWSAlertFeature
import com.example.data.NWSAlertProperties
import com.example.data.NWSService
import com.example.data.SavedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NWSAlertMapperTest {

    private val location = SavedLocation(
        id = 42L,
        name = "Outer Banks, NC",
        latitude = 35.5585,
        longitude = -75.4665
    )

    @Test
    fun `preserves full official warning details for the detail screen`() {
        val fullDescription = "A".repeat(900)
        val feature = NWSAlertFeature(
            id = "https://api.weather.gov/alerts/urn:oid:test-alert",
            properties = NWSAlertProperties(
                id = "urn:oid:test-alert",
                event = "Hurricane Warning",
                severity = "Extreme",
                certainty = "Observed",
                urgency = "Immediate",
                headline = "Hurricane Warning issued for the Outer Banks",
                description = fullDescription,
                instruction = "Complete preparations and follow evacuation orders.",
                senderName = "NWS Newport/Morehead City NC",
                effective = "2026-07-14T12:00:00Z",
                expires = "2026-07-15T00:00:00Z",
                areaDesc = "Dare County; Hyde County"
            )
        )

        val alert = NWSService.toActiveAlert(
            feature = feature,
            storedId = "nws_42_urn:oid:test-alert",
            location = location
        )

        assertEquals("Hurricane Warning", alert.title)
        assertEquals("extreme", alert.severity)
        assertEquals(fullDescription, alert.description)
        assertEquals("Immediate", alert.urgency)
        assertEquals("Observed", alert.certainty)
        assertEquals("Dare County; Hyde County", alert.areaDesc)
        assertEquals("Complete preparations and follow evacuation orders.", alert.instruction)
        assertEquals("https://api.weather.gov/alerts/urn:oid:test-alert", alert.sourceUrl)
        assertEquals(42L, alert.locationId)
        assertEquals("Outer Banks, NC", alert.locationName)
    }

    @Test
    fun `uses headline as detail body when NWS omits description`() {
        val feature = NWSAlertFeature(
            id = null,
            properties = NWSAlertProperties(
                id = "urn:oid:statement",
                event = null,
                severity = null,
                certainty = null,
                urgency = null,
                headline = "Coastal Flood Statement remains in effect",
                description = null,
                instruction = null,
                senderName = null,
                effective = null,
                expires = null,
                areaDesc = null
            )
        )

        val alert = NWSService.toActiveAlert(feature, "nws_42_statement", location)

        assertEquals("Coastal Flood Statement remains in effect", alert.title)
        assertEquals("Coastal Flood Statement remains in effect", alert.description)
        assertEquals("minor", alert.severity)
        assertNull(alert.sourceUrl)
    }
}
