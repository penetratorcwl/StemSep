package app.stemsep

import android.content.Context
import app.stemsep.separation.SeparationPipeline
import app.stemsep.separation.StemStorage
import app.stemsep.separation.StemExporter

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val storage = StemStorage(appContext)
    val pipeline = SeparationPipeline(appContext, storage)
    val exporter = StemExporter(appContext)
}
