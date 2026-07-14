package com.example

import com.example.data.WeatherRepository
import com.example.ui.StormUIState
import com.example.ui.stormUiStateFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StormUiStateTest {

    @Test
    fun `fresh empty response is a verified clear state`() {
        val state = stormUiStateFrom(
            WeatherRepository.StormSyncResult.Fresh(
                storms = emptyList(),
                lastVerifiedMillis = 1234L
            )
        )

        assertTrue(state is StormUIState.NoStorms)
        state as StormUIState.NoStorms
        assertFalse(state.isCached)
        assertEquals(1234L, state.lastVerifiedMillis)
    }

    @Test
    fun `cached empty response is labeled as a snapshot`() {
        val state = stormUiStateFrom(
            WeatherRepository.StormSyncResult.Cached(
                storms = emptyList(),
                lastVerifiedMillis = 1234L,
                reason = "Offline"
            )
        )

        assertTrue(state is StormUIState.NoStorms)
        state as StormUIState.NoStorms
        assertTrue(state.isCached)
    }

    @Test
    fun `unverified empty response is an error instead of all clear`() {
        val state = stormUiStateFrom(
            WeatherRepository.StormSyncResult.Unavailable("NHC could not be reached")
        )

        assertTrue(state is StormUIState.Error)
        assertEquals("NHC could not be reached", (state as StormUIState.Error).message)
    }
}
