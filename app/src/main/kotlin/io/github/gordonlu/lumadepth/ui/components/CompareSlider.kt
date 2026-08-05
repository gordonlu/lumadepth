// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * 原图 / HDR 可拖动分割线对比。
 */
@Composable
fun CompareSlider(
    original: Bitmap,
    enhanced: Bitmap,
    modifier: Modifier = Modifier,
) {
    var split by remember { mutableFloatStateOf(0.5f) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    if (size.width > 0) {
                        split = (change.position.x / size.width).coerceIn(0f, 1f)
                        change.consume()
                    }
                }
            }
    ) {
        // HDR 效果（全图）
        Image(
            bitmap = enhanced.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        // 原图（只绘制左侧部分）
        Image(
            bitmap = original.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(right = size.width * split) {
                        this@drawWithContent.drawContent()
                    }
                },
        )
        // 分割线
        Box(
            modifier = Modifier
                .offset { IntOffset((size.width * split).toInt() - 1, 0) }
                .width(2.dp)
                .fillMaxHeight()
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawLine(
                    color = Color.White,
                    start = Offset(1f, 0f),
                    end = Offset(1f, size.height.toFloat()),
                    strokeWidth = 2f,
                )
            }
        }
    }
}
