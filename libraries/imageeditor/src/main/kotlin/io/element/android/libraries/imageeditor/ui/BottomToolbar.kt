/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.element.android.libraries.imageeditor.AspectRatioPreset
import io.element.android.libraries.imageeditor.state.EditorState
import io.element.android.libraries.imageeditor.state.EditorTool

/**
 * Bottom toolbar for the editor. Renders two rows:
 *   1. Context-specific secondary controls (color/stroke for draw, aspect chips
 *      for crop, rotate/flip for rotate, add-text for text).
 *   2. Primary tool selector with the four tools.
 */
@Composable
fun BottomToolbar(
    state: EditorState,
    onAddText: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SecondaryRow(state = state, onAddText = onAddText)
        PrimaryToolRow(
            active = state.activeTool,
            onSelect = { tool ->
                state.selectTool(if (state.activeTool == tool) EditorTool.None else tool)
            },
        )
    }
}

@Composable
private fun PrimaryToolRow(
    active: EditorTool,
    onSelect: (EditorTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton(
            label = "Crop",
            isActive = active == EditorTool.Crop,
            onClick = { onSelect(EditorTool.Crop) },
            icon = { Icon(Icons.Filled.Crop, contentDescription = "Crop") },
        )
        ToolButton(
            label = "Rotate",
            isActive = active == EditorTool.Rotate,
            onClick = { onSelect(EditorTool.Rotate) },
            icon = { Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate") },
        )
        ToolButton(
            label = "Draw",
            isActive = active == EditorTool.Draw,
            onClick = { onSelect(EditorTool.Draw) },
            icon = { Icon(Icons.Filled.Edit, contentDescription = "Draw") },
        )
        ToolButton(
            label = "Marker",
            isActive = active == EditorTool.Highlighter,
            onClick = { onSelect(EditorTool.Highlighter) },
            icon = { Icon(Icons.Filled.BorderColor, contentDescription = "Highlighter") },
        )
        ToolButton(
            label = "Text",
            isActive = active == EditorTool.Text,
            onClick = { onSelect(EditorTool.Text) },
            icon = { Icon(Icons.Filled.TextFields, contentDescription = "Text") },
        )
    }
}

@Composable
private fun ToolButton(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        val tint = if (isActive) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides tint,
        ) { icon() }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}

@Composable
private fun SecondaryRow(
    state: EditorState,
    onAddText: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state.activeTool) {
            EditorTool.Crop -> CropControls(state)
            EditorTool.Rotate -> RotateControls(state)
            EditorTool.Draw -> DrawControls(state)
            EditorTool.Highlighter -> HighlighterControls(state)
            EditorTool.Text -> TextControls(state, onAddText)
            EditorTool.None -> Spacer(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CropControls(state: EditorState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { state.resetCrop() }) {
            Icon(Icons.Filled.Refresh, contentDescription = "Reset", modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Reset")
        }
        for (preset in state.config.aspectRatios) {
            FilterChip(
                selected = state.selectedAspectRatio == preset,
                onClick = { state.selectedAspectRatio = preset },
                label = { Text(preset.label) },
            )
        }
    }
}

@Composable
private fun RotateControls(state: EditorState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { state.rotate90Clockwise() }) {
            Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Rotate 90°")
        }
        TextButton(onClick = { state.toggleHorizontalFlip() }) {
            Icon(Icons.Filled.Flip, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Flip")
        }
    }
}

@Composable
private fun DrawControls(state: EditorState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { state.undoLastPath() }) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
        }
        ColorSwatchRow(
            colors = state.config.drawingPalette,
            selected = state.drawColor,
            onSelect = state::setDrawColor,
        )
        Spacer(Modifier.width(8.dp))
        StrokeWidthRow(
            widths = state.config.strokeWidthsDp,
            selected = state.drawStrokeWidthDp,
            onSelect = state::setDrawStrokeWidth,
        )
    }
}

@Composable
private fun HighlighterControls(state: EditorState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { state.undoLastPath() }) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
        }
        ColorSwatchRow(
            colors = state.config.drawingPalette,
            selected = state.highlighterColor,
            onSelect = state::setHighlighterColor,
        )
        Spacer(Modifier.width(8.dp))
        StrokeWidthRow(
            widths = state.config.highlighterStrokeWidthsDp,
            selected = state.highlighterStrokeWidthDp,
            onSelect = state::setHighlighterStrokeWidth,
        )
    }
}

@Composable
private fun TextControls(state: EditorState, onAddText: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onAddText) {
            Icon(Icons.Filled.TextFields, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add text")
        }
        if (state.textItems.isNotEmpty()) {
            Text(
                "${state.textItems.size} item(s) — tap to edit",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ColorSwatchRow(
    colors: List<Color>,
    selected: Color,
    onSelect: (Color) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        colors.forEach { c ->
            val isSelected = c == selected
            Spacer(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(c)
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = CircleShape,
                    )
                    .clickable { onSelect(c) },
            )
        }
    }
}

@Composable
private fun StrokeWidthRow(
    widths: List<Float>,
    selected: Float,
    onSelect: (Float) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        widths.forEach { w ->
            val isSelected = w == selected
            // Render each preset as a horizontal pill. Width is fixed so the row
            // stays the same size for both the Draw (3-20dp) and Highlighter
            // (16-52dp) presets; height grows with the stroke value but is clamped
            // so even the thickest preview fits inside the toolbar.
            val barHeight = (w * 0.35f).coerceIn(3f, 14f).dp
            Spacer(
                modifier = Modifier
                    .size(width = 30.dp, height = barHeight)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    .clickable { onSelect(w) },
            )
        }
    }
}
