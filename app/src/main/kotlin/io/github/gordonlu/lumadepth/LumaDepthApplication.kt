package io.github.gordonlu.lumadepth

import android.app.Application
import android.content.Context
import io.github.gordonlu.lumadepth.image.UltraHdrPipeline
import io.github.gordonlu.lumadepth.image.analysis.ImageAnalyzer
import io.github.gordonlu.lumadepth.image.decode.BitmapDecoder
import io.github.gordonlu.lumadepth.image.encode.UltraHdrEncoder
import io.github.gordonlu.lumadepth.image.gainmap.GainMapRenderer
import io.github.gordonlu.lumadepth.image.tonemap.PreviewRenderer
import io.github.gordonlu.lumadepth.storage.MediaStoreSaver

class LumaDepthApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** 简单依赖容器：构造函数注入，无 DI 框架。 */
class AppContainer(context: Context) {
    val pipeline: UltraHdrPipeline by lazy {
        UltraHdrPipeline(
            context = context,
            decoder = BitmapDecoder(context),
            analyzer = ImageAnalyzer(BitmapDecoder(context)),
            previewRenderer = PreviewRenderer(),
            gainMapRenderer = GainMapRenderer(),
            encoder = UltraHdrEncoder(),
            saver = MediaStoreSaver(context),
        )
    }
}
