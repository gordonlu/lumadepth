// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth

import android.app.Application
import android.content.Context
import android.os.StrictMode
import android.util.Log
import io.github.gordonlu.lumadepth.image.UltraHdrPipeline
import io.github.gordonlu.lumadepth.image.analysis.ImageAnalyzer
import io.github.gordonlu.lumadepth.image.decode.BitmapDecoder
import io.github.gordonlu.lumadepth.image.encode.UltraHdrEncoder
import io.github.gordonlu.lumadepth.image.gainmap.GainMapRenderer
import io.github.gordonlu.lumadepth.image.tonemap.PreviewRenderer
import io.github.gordonlu.lumadepth.storage.MediaStoreSaver
import java.io.File

class LumaDepthApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        cleanupStaleTempFiles()
        if (BuildConfig.DEBUG) {
            enableDebugChecks()
        }
    }

    /**
     * 应用下次启动时清理上次遗留的临时文件（缓存目录，不含隐私信息）。
     */
    private fun cleanupStaleTempFiles() {
        Thread {
            try {
                File(cacheDir, "lumadepth").listFiles()?.forEach { it.delete() }
            } catch (e: Exception) {
                Log.w(TAG, "temp cleanup failed: ${e.message}")
            }
        }.start()
    }

    /** Debug 构建启用开发期检查（不影响 Release，不收集用户数据）。 */
    private fun enableDebugChecks() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .build()
        )
    }

    companion object {
        private const val TAG = "LumaDepth"
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
