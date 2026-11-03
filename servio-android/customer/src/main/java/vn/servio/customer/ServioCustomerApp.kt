package vn.servio.customer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.osmdroid.config.Configuration
import java.io.File

@HiltAndroidApp
class ServioCustomerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        configureOsmdroid()
    }

    /** osmdroid (CS-12, CS-21): identifies the app to the tile servers and keeps map tiles in app-private storage. */
    private fun configureOsmdroid() {
        Configuration.getInstance().apply {
            load(this@ServioCustomerApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = BuildConfig.APPLICATION_ID
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
    }
}
