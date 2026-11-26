package com.meteorsss.startrace

/** Exactly two providers. OSM Standard is raster Carto, not a different location provider. */
enum class MapBasemap(val id: String, val label: String) {
    OSM_STANDARD("osm-standard", "OpenStreetMap 标准"),
    OPEN_FREE_MAP("openfreemap", "OpenFreeMap");

    companion object {
        fun fromId(id: String?): MapBasemap = entries.firstOrNull { it.id == id } ?: OSM_STANDARD
        const val OSM_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        val OSM_STYLE = """{
          "version":8,"name":"OpenStreetMap Standard",
          "sources":{"osm-standard":{"type":"raster","tiles":["$OSM_TILE_URL"],
            "tileSize":256,"minzoom":0,"maxzoom":19,
            "attribution":"© OpenStreetMap contributors"}},
          "layers":[{"id":"osm-standard","type":"raster","source":"osm-standard",
            "paint":{"raster-fade-duration":0}}]
        }""".trimIndent()
    }
}
