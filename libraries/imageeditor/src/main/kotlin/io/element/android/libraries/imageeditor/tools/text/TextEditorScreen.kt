/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.tools.text

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Full-screen text editor inspired by hm21/pro_image_editor. Lets the user enter
 * text and tweak font size, color, alignment and background mode in one place.
 *
 * @param initial existing item being edited, or null when creating a new one
 * @param palette colors offered as quick-pick swatches
 * @param onSubmit called with the edited/created item; for new items the [TextItem.id]
 *                 is unused and will be assigned by the state holder
 * @param onDelete shown only when [initial] is non-null
 * @param onDismiss called when the user cancels
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    var text by remember { mutableStateOf(initial?.text.orEmpty()) }
    var color by remember { mutableStateOf(defaultColor) }
    var fontSize by remember { mutableStateOf(defaultSize) }
    var align by remember { mutableStateOf(defaultAlign) }

    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        // Wait one frame so the TextField has been laid out and is focusable,
        // then both move focus to it AND explicitly tell the IME to show — on
        // some Android versions requestFocus() alone does not bring up the
        // soft keyboard reliably.
        delay(80)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    BackHandler { onDismiss() }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { align = align.cycle() }) {
                        Icon(
                            imageVector = align.icon(),
                            contentDescription = "Align",
                            tint = Color.White,
                        )
                    }
                    IconButton(
                        enabled = text.isNotBlank(),
                        onClick = {
                            val item = (initial ?: TextItem(
                                id = 0L,
                                text = text,
                                color = color,
                                fontSizeSp = fontSize,
                                position = Offset(0.4f, 0.45f),
                            )).copy(
                                text = text,
                                color = color,
                                fontSizeSp = fontSize,
                                align = align,
                            )
                            onSubmit(item)
                        },
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Done",
                            tint = if (text.isNotBlank()) Color.White else Color.Gray,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                ),
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Size: ${fontSize.toInt()} sp",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = fontSize,
                    onValueChange = { fontSize = it },
                    valueRange = 16f..120f,
                )
                ColorPaletteRow(
                    palette = palette,
                    selected = color,
                    onSelect = { color = it },
                )
                if (initial != null && onDelete != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        IconButton(onClick = { onDelete(initial) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        containerColor = Color.Black,
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                textStyle = TextStyle(
                    color = color,
                    fontSize = fontSize.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = align,
                ),
                cursorBrush = SolidColor(color),
                decorationBox = { innerTextField ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = "Type something",
                                color = color.copy(alpha = 0.4f).compositeOver(Color.Black),
                                style = TextStyle(
                                    fontSize = fontSize.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = align,
                                ),
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
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
