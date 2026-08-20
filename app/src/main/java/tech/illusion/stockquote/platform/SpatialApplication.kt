package tech.illusion.stockquote.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import tech.illusion.stockquote.data.StockRepository
import tech.illusion.stockquote.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StockRepository.init(this)
        launch(::mainApp)
    }
}
