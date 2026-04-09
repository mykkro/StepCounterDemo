package com.example.stepcounterdemo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.stepcounterdemo.R
import com.example.stepcounterdemo.data.HourlyStepEntity

/**
 * Full-screen bar chart showing step counts for the 24 hours ending at [currentHour].
 * Each bar represents one hour; the current hour is highlighted in primary colour.
 */
@Composable
fun HourlyStepChart(
    entries: List<HourlyStepEntity>,
    currentHour: Long,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fromHour = currentHour - 23L
    val hourRange = fromHour..currentHour
    val hourMap = entries.associate { it.hourKey to it.stepCount }
    val counts = hourRange.map { hourMap[it] ?: 0 }
    val maxCount = counts.maxOrNull()?.takeIf { it > 0 } ?: 1

    val textMeasurer = rememberTextMeasurer()
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val onSurface = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.last_24_hours_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))

            Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val labelAreaHeight = 28.dp.toPx()
                val barAreaHeight = canvasHeight - labelAreaHeight
                val slotWidth = canvasWidth / 24f
                val barWidth = slotWidth * 0.65f

                // X-axis baseline
                drawLine(
                    color = onSurface,
                    start = Offset(0f, barAreaHeight),
                    end = Offset(canvasWidth, barAreaHeight),
                    strokeWidth = 1.dp.toPx()
                )

                hourRange.forEachIndexed { index, hourKey ->
                    val count = counts[index]
                    val slotLeft = index * slotWidth
                    val barLeft = slotLeft + (slotWidth - barWidth) / 2f
                    val barHeight = (count.toFloat() / maxCount) * (barAreaHeight - 12.dp.toPx())
                    val color = if (hourKey == currentHour) primaryColor else secondaryColor

                    if (barHeight > 0f) {
                        drawRect(
                            color = color,
                            topLeft = Offset(barLeft, barAreaHeight - barHeight),
                            size = Size(barWidth, barHeight)
                        )
                    }

                    // Hour label (0–23)
                    val hourLabel = (hourKey % 24L).toString()
                    val labelResult = textMeasurer.measure(
                        AnnotatedString(hourLabel),
                        style = TextStyle(fontSize = 8.sp, color = onSurface)
                    )
                    drawText(
                        textLayoutResult = labelResult,
                        topLeft = Offset(
                            slotLeft + slotWidth / 2f - labelResult.size.width / 2f,
                            barAreaHeight + 6.dp.toPx()
                        )
                    )

                    // Step count above bar (omit if zero)
                    if (count > 0) {
                        val countResult = textMeasurer.measure(
                            AnnotatedString(count.toString()),
                            style = TextStyle(fontSize = 7.sp, color = onSurface)
                        )
                        drawText(
                            textLayoutResult = countResult,
                            topLeft = Offset(
                                slotLeft + slotWidth / 2f - countResult.size.width / 2f,
                                barAreaHeight - barHeight - countResult.size.height - 2.dp.toPx()
                            )
                        )
                    }
                }
            }
        }

        // Close button top-right
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            Text("✕", style = MaterialTheme.typography.titleMedium)
        }
    }
}
