package com.meteorsss.startrace

import org.junit.Assert.*
import org.junit.Test

class MapBasemapTest {
    @Test fun onlyRequestedTwoProvidersExist() {
        assertEquals(listOf("osm-standard", "openfreemap"), MapBasemap.entries.map { it.id })
    }
    @Test fun selectionHasStableIdsAndSafeDefault() {
        MapBasemap.entries.forEach { assertEquals(it, MapBasemap.fromId(it.id)) }
        assertEquals(MapBasemap.OSM_STANDARD, MapBasemap.fromId(null))
        assertEquals(MapBasemap.OSM_STANDARD, MapBasemap.fromId("retired-provider"))
    }
    @Test fun osmUsesCanonicalHttpsRasterTilesAtNativeResolution() {
        val json = com.google.gson.JsonParser.parseString(MapBasemap.OSM_STYLE).asJsonObject
        val source = json.getAsJsonObject("sources").getAsJsonObject("osm-standard")
        assertEquals("raster", source["type"].asString)
        assertEquals("https://tile.openstreetmap.org/{z}/{x}/{y}.png", source["tiles"].asJsonArray[0].asString)
        assertEquals(256, source["tileSize"].asInt)
        assertEquals(19, source["maxzoom"].asInt)
        assertTrue(source["attribution"].asString.contains("OpenStreetMap contributors"))
    }
}
