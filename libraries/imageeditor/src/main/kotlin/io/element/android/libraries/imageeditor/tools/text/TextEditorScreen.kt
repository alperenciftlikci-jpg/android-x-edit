/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.tools.text

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import io.element.android.libraries.core.perf.PerfRegistry
import io.element.android.libraries.core.perf.TracedGesture
import io.element.android.libraries.core.perf.trace
import kotlinx.coroutines.flow.drop

/**
 * Full-screen text editor.
 *
 * **Why a native `EditText` instead of Compose `BasicTextField`.** Even after migrating to
 * `TextFieldState`, stripping Material3 Scaffold/TopAppBar, stabilising the decorator/textStyle/
 * cursorBrush, and converting to the overlay pattern, typing in this editor still felt sluggish
 * and `text.editor.typing.window` was reading 5–25 fps on the dashboard. The remaining culprit
 * is structural: Compose Foundation's `BasicTextField` cursor uses an `InfiniteTransition`
 * internally that flips its alpha every ~500 ms while the field is focused. There is no public
 * API to disable it. Each blink ticks a snapshot mutation that propagates through the field's
 * sub-tree and forces a recomposition of the decoration / text-layout pipeline. Combined with
 * the IME-compose-mode events Turkish characters generate (each composing keystroke is its own
 * snapshot mutation), the cumulative recomposition cost dragged the typing path well below
 * 60 fps on real devices.
 *
 * Native `EditText` has none of those costs:
 *  - Cursor blink is handled by the Android system at the View/RenderThread level — it never
 *    enters the Compose tree, so it can't invalidate Compose state observers.
 *  - IME compose mode is processed inside `EditText`'s buffer, then a single
 *    `afterTextChanged` event fires per character — vs Compose, where each composing keystroke
 *    is a snapshot transaction.
 *  - Text rendering is the same `TextView` pipeline that powers every Android app since 1.0,
 *    deeply optimised for hardware-accelerated draw.
 *
 * The single Compose `String` state mirroring the EditText's text is what we observe via
 * `snapshotFlow` for the per-keystroke FPS trace and the Apply-button enable state. The
 * EditText is the source of truth; Compose only reads.
 *
 * @param initial existing item being edited, or null when creating a new one
 * @param palette colors offered as quick-pick swatches
 * @param onSubmit called with the edited/created item; for new items the [TextItem.id]
 *                 is unused and will be assigned by the state holder
 * @param onDelete shown only when [initial] is non-null
 * @param onDismiss called when the user cancels
 */
@Composable
fun TextEditorScreen(
    initial: TextItem?,
    palette: List<Color>,
    onSubmit: (TextItem) -> Unit,
    onDelete: ((TextItem) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val defaultColor = initial?.color ?: palette.firstOrNull() ?: Color.White
    val defaultSize = initial?.fontSizeSp ?: 36f
    val defaultAlign = initial?.align ?: TextAlign.Center

    // Compose state mirroring the EditText. EditText is source of truth; this state is updated
    // from `doAfterTextChanged` and read by snapshot observers (Apply button, keystroke trace).
    var text by remember { mutableStateOf(initial?.text.orEmpty()) }
    var color by remember { mutableStateOf(defaultColor) }
    var fontSize by remember { mutableStateOf(defaultSize) }
    var align by remember { mutableStateOf(defaultAlign) }

    // Hold a reference to the EditText so we can request focus / show IME / read final value.
    val editTextRef = remember { mutableStateOf<EditText?>(null) }

    val typingSession = remember {
        TracedGesture(name = "imageeditor.text.editor.typing", recordFps = false)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        typingSession.start()
        onDispose { typingSession.finish() }
    }

    // Per-keystroke frame-aware FPS trace. snapshotFlow observes the mirrored text state, which
    // is updated by EditText's doAfterTextChanged. Each text change opens a 2-frame trace —
    // donmaya sebep olan render uzaması burada fps düşüşü olarak görünür.
    LaunchedEffect(Unit) {
        snapshotFlow { text }
            .drop(1)
            .collect {
                val tg = TracedGesture("imageeditor.text.editor.keystroke.frame")
                tg.start()
                withFrameNanos { /* render frame after the keystroke */ }
                withFrameNanos { /* second frame */ }
                tg.finish()
                PerfRegistry.record("imageeditor.text.editor.keystroke", 0L)
            }
    }

    // Focus + IME show — wait two frames so the EditText has been attached, then ask the
    // InputMethodManager directly. EditText's own requestFocus is what triggers the soft
    // keyboard; LocalSoftwareKeyboardController is Compose-only and not relevant here.
    LaunchedEffect(Unit) {
        trace("imageeditor.text.editor.open") {
            withFrameNanos { }
            withFrameNanos { }
            editTextRef.value?.let { et ->
                et.requestFocus()
                // Move the cursor to the end so the user types after any pre-filled text.
                et.setSelection(et.text?.length ?: 0)
                val imm = et.context.getSystemService(InputMethodManager::class.java)
                imm?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    BackHandler { trace("imageeditor.text.editor.dismiss") { onDismiss() } }

    val isBlank by remember { derivedStateOf { text.isBlank() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
            .imePadding(),
    ) {
        // Top bar
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
                onClick = { trace("imageeditor.text.editor.dismiss") { onDismiss() } },
            )
            Spacer(modifier = Modifier.weight(1f))
            BarIconButton(
                icon = align.icon(),
                contentDescription = "Align",
                tint = Color.White,
                onClick = { trace("imageeditor.text.editor.align.cycle") { align = align.cycle() } },
            )
            BarIconButton(
                icon = Icons.Filled.Check,
                contentDescription = "Done",
                tint = if (!isBlank) Color.White else Color.White.copy(alpha = 0.4f),
                enabled = !isBlank,
                onClick = {
                    trace("imageeditor.text.editor.submit") {
                        val finalText = editTextRef.value?.text?.toString().orEmpty()
                        val item = (initial ?: TextItem(
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

        // Content — native EditText hosted in AndroidView. weight(1f) lets it absorb all the
        // remaining vertical space. The Box wrapper centres the EditText vertically inside the
        // available area, matching the previous BasicTextField look.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Initial values captured by remember-of-Unit so the factory only runs once with
            // these as the bootstrapping values. Subsequent state changes flow through `update`.
            val initialText = remember { initial?.text.orEmpty() }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                factory = { ctx ->
                    EditText(ctx).apply {
                        // Transparent chrome so the EditText looks like the previous Compose
                        // text field — just text + cursor on black.
                        background = null
                        isHorizontalScrollBarEnabled = false
                        isVerticalScrollBarEnabled = false
                        // Multi-line + sentence capitalisation, like a chat composer.
                        inputType = InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        setSingleLine(false)
                        // No autofill / no spell-check assist UI — keep the input pure.
                        importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        hint = "Type something"
                        if (initialText.isNotEmpty()) {
                            setText(initialText)
                            setSelection(initialText.length)
                        }
                        // Mirror EditText changes to Compose state. Guard against the no-op
                        // case so we don't loop or trigger snapshot mutations for unchanged
                        // text — `setText` from `update` would otherwise re-fire this.
                        doAfterTextChanged { editable ->
                            val newText = editable?.toString().orEmpty()
                            if (newText != text) text = newText
                        }
                        editTextRef.value = this
                    }
                },
                update = { editText ->
                    // Style sync. None of these operations trigger a layout pass on EditText
                    // unless the resolved value actually changes — `setTextColor(sameColor)` is
                    // a fast no-op. So this lambda is cheap to run on every recomposition.
                    val argb = color.toArgb()
                    editText.setTextColor(argb)
                    editText.setHintTextColor(color.copy(alpha = 0.4f).toArgb())
                    editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
                    editText.gravity = align.toGravity()
                },
            )
        }

        // Bottom bar — size slider + palette + optional delete row.
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
            // Slider draft pattern — drag boyunca local liveFontSize, drag-end'de fontSize'e
            // commit. Slider drag sırasında EditText'in textSize'ı her event'te değişmiyor →
            // EditText layout pass tetiklenmiyor. Bu typing performansını korur.
            val liveFontSize = remember { mutableStateOf<Float?>(null) }
            Slider(
                value = liveFontSize.value ?: fontSize,
                onValueChange = { liveFontSize.value = it },
                valueRange = 16f..120f,
                onValueChangeFinished = {
                    liveFontSize.value?.let { fontSize = it }
                    liveFontSize.value = null
                    PerfRegistry.record("imageeditor.text.editor.size.commit", 0L)
                },
            )
            ColorPaletteRow(
                palette = palette,
                selected = color,
                onSelect = { picked ->
                    trace("imageeditor.text.editor.color.tap") { color = picked }
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
                        onClick = { trace("imageeditor.text.editor.delete") { onDelete(initial) } },
                    )
                }
            }
        }
    }
}

/**
 * Lightweight icon button substitute for Material3 IconButton.
 */
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
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
        )
    }
}

@Composable
private fun ColorPaletteRow(
    palette: List<Color>,
    selected: Color,
    onSelect: (Color) -> Unit,
) {
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
