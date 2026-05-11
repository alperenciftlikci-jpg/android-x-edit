/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.last.tools.text

import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import io.element.android.libraries.core.perf.PerfRegistry
import io.element.android.libraries.core.perf.TracedGesture
import io.element.android.libraries.core.perf.trace
import io.element.android.libraries.imageeditor.last.LastTextItem
import kotlinx.coroutines.flow.drop

/**
 * Full-screen text editor — native EditText hosted in an AndroidView. Same architecture as the
 * baseline editor's final form (after the BasicTextField → EditText migration that fixed the
 * Compose cursor-blink jank).
 */
@Composable
fun LastTextEditorScreen(
    initial: LastTextItem?,
    palette: List<Color>,
    onSubmit: (LastTextItem) -> Unit,
    onDelete: ((LastTextItem) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val defaultColor = initial?.color ?: palette.firstOrNull() ?: Color.White
    val defaultSize = initial?.fontSizeSp ?: 36f
    val defaultAlign = initial?.align ?: TextAlign.Center

    var text by remember { mutableStateOf(initial?.text.orEmpty()) }
    var color by remember { mutableStateOf(defaultColor) }
    var fontSize by remember { mutableStateOf(defaultSize) }
    var align by remember { mutableStateOf(defaultAlign) }

    val editTextRef = remember { mutableStateOf<EditText?>(null) }

    val typingSession = remember {
        TracedGesture(name = "imageeditor.last.text.editor.typing", recordFps = false)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        typingSession.start()
        onDispose { typingSession.finish() }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { text }
            .drop(1)
            .collect {
                val tg = TracedGesture("imageeditor.last.text.editor.keystroke.frame")
                tg.start()
                withFrameNanos { }
                withFrameNanos { }
                tg.finish()
                PerfRegistry.record("imageeditor.last.text.editor.keystroke", 0L)
            }
    }

    LaunchedEffect(Unit) {
        trace("imageeditor.last.text.editor.open") {
            withFrameNanos { }
            withFrameNanos { }
            editTextRef.value?.let { et ->
                et.requestFocus()
                et.setSelection(et.text?.length ?: 0)
                val imm = et.context.getSystemService(InputMethodManager::class.java)
                imm?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    BackHandler { trace("imageeditor.last.text.editor.dismiss") { onDismiss() } }

    val isBlank by remember { derivedStateOf { text.isBlank() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarIconButton(
                icon = Icons.Filled.Close,
                contentDescription = "Cancel",
                tint = Color.White,
                onClick = { trace("imageeditor.last.text.editor.dismiss") { onDismiss() } },
            )
            Spacer(modifier = Modifier.weight(1f))
            BarIconButton(
                icon = align.icon(),
                contentDescription = "Align",
                tint = Color.White,
                onClick = { trace("imageeditor.last.text.editor.align.cycle") { align = align.cycle() } },
            )
            BarIconButton(
                icon = Icons.Filled.Check,
                contentDescription = "Done",
                tint = if (!isBlank) Color.White else Color.White.copy(alpha = 0.4f),
                enabled = !isBlank,
                onClick = {
                    trace("imageeditor.last.text.editor.submit") {
                        val finalText = editTextRef.value?.text?.toString().orEmpty()
                        val item = (initial ?: LastTextItem(
                            id = 0L,
                            text = finalText,
                            color = color,
                            fontSizeSp = fontSize,
                            position = Offset(0.4f, 0.45f),
                        )).copy(
                            text = finalText,
                            color = color,
                            fontSizeSp = fontSize,
                            align = align,
                        )
                        onSubmit(item)
                    }
                },
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val initialText = remember { initial?.text.orEmpty() }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                factory = { ctx ->
                    EditText(ctx).apply {
                        background = null
                        isHorizontalScrollBarEnabled = false
                        isVerticalScrollBarEnabled = false
                        inputType = InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        setSingleLine(false)
                        importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        hint = "Type something"
                        if (initialText.isNotEmpty()) {
                            setText(initialText)
                            setSelection(initialText.length)
                        }
                        doAfterTextChanged { editable ->
                            val newText = editable?.toString().orEmpty()
                            if (newText != text) text = newText
                        }
                        editTextRef.value = this
                    }
                },
                update = { editText ->
                    val argb = color.toArgb()
                    editText.setTextColor(argb)
                    editText.setHintTextColor(color.copy(alpha = 0.4f).toArgb())
                    editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
                    editText.gravity = align.toGravity()
                },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(
                text = "Size: ${fontSize.toInt()} sp",
                style = TextStyle(color = Color.White, fontSize = 12.sp),
            )
            val liveFontSize = remember { mutableStateOf<Float?>(null) }
            Slider(
                value = liveFontSize.value ?: fontSize,
                onValueChange = { liveFontSize.value = it },
                valueRange = 16f..120f,
                onValueChangeFinished = {
                    liveFontSize.value?.let { fontSize = it }
                    liveFontSize.value = null
                    PerfRegistry.record("imageeditor.last.text.editor.size.commit", 0L)
                },
            )
            ColorPaletteRow(
                palette = palette,
                selected = color,
                onSelect = { picked ->
                    trace("imageeditor.last.text.editor.color.tap") { color = picked }
                },
            )
            if (initial != null && onDelete != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    BarIconButton(
                        icon = Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = Color(0xFFEF5350),
                        onClick = { trace("imageeditor.last.text.editor.delete") { onDelete(initial) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun BarIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint)
    }
}

@Composable
private fun ColorPaletteRow(palette: List<Color>, selected: Color, onSelect: (Color) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        palette.forEach { swatch ->
            val isSelected = swatch == selected
            Spacer(
                modifier = Modifier
                    .size(if (isSelected) 36.dp else 28.dp)
                    .clip(CircleShape)
                    .background(swatch)
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.3f),
                        shape = CircleShape,
                    )
                    .clickable { onSelect(swatch) },
            )
        }
    }
}

private fun TextAlign.cycle(): TextAlign = when (this) {
    TextAlign.Left, TextAlign.Start -> TextAlign.Center
    TextAlign.Center -> TextAlign.Right
    TextAlign.Right, TextAlign.End -> TextAlign.Left
    else -> TextAlign.Center
}

private fun TextAlign.icon() = when (this) {
    TextAlign.Left, TextAlign.Start -> Icons.AutoMirrored.Filled.FormatAlignLeft
    TextAlign.Right, TextAlign.End -> Icons.AutoMirrored.Filled.FormatAlignRight
    else -> Icons.Filled.FormatAlignCenter
}

private fun TextAlign.toGravity(): Int = when (this) {
    TextAlign.Left, TextAlign.Start -> Gravity.START or Gravity.CENTER_VERTICAL
    TextAlign.Right, TextAlign.End -> Gravity.END or Gravity.CENTER_VERTICAL
    else -> Gravity.CENTER
}
