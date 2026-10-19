package vn.servio.customer.ui.map

import androidx.annotation.StringRes
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import vn.servio.customer.R

/** The one place that decides where map tiles come from (CS-12, CS-21) and how they are credited. */
object MapConfig {

    /** tile.openstreetmap.org, the default server of OpenStreetMap. */
    private val OPENSTREETMAP_ORG: ITileSource = TileSourceFactory.MAPNIK

    /** German mirror of the same map data. Use it when a network blocks openstreetmap.org. */
    private val OPENSTREETMAP_DE: ITileSource = XYTileSource(
        "OpenStreetMapDE", 0, 19, 256, ".png", arrayOf("https://tile.openstreetmap.de/"), "© OpenStreetMap contributors",
    )

    // Change only this line to switch the tile server (OPENSTREETMAP_ORG or OPENSTREETMAP_DE).
    val TILE_SOURCE: ITileSource = OPENSTREETMAP_ORG

    /** Credit shown on top of every map. Both servers above serve OpenStreetMap data, so the text is the same. */
    @StringRes
    val ATTRIBUTION: Int = R.string.map_attribution
}
