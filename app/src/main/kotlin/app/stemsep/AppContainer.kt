package app.stemsep

import android.content.Context
import app.stemsep.separation.SeparationPipeline
import app.stemsep.separation.StemCache
import app.stemsep.separation.StemExporter

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val cache = StemCache(appContext)
    val pipeline = SeparationPipeline(appContext, cache)
    val exporter = StemExporter(appContext)
}
