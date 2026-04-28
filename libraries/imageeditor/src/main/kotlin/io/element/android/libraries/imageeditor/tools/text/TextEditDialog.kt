/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.tools.text

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Modal dialog that lets the user create or edit a [TextItem].
 *
 * @param initial existing item being edited, or null when creating a new one
 * @param palette colors offered as quick-pick swatches
 * @param onSubmit called with the edited/created item; for new items the [TextItem.id]
 *                 is unused and will be assigned by the state holder
 * @param onDelete shown only when [initial] is non-null
 * @param onDismiss called when the user cancels
 */
@Composable
fun TextEditDialog(
    initial: TextItem?,
    palette: List<Color>,
    onSubmit: (TextItem) -> Unit,
    onDelete: ((TextItem) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val defaultColor = initial?.color ?: palette.firstOrNull() ?: Color.White
    val defaultSize = initial?.fontSizeSp ?: 28f

    var text by remember { mutableStateOf(initial?.text.orEmpty()) }
    var color by remember { mutableStateOf(defaultColor) }
    var fontSize by remember { mutableStateOf(defaultSize) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add text" else "Edit text") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(16.dp))
                Text("Color", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    palette.forEach { swatch ->
                        val isSelected = swatch == color
                        Spacer(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                    shape = CircleShape,
                                )
                                .clickable { color = swatch },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("Size: ${fontSize.toInt()} sp", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = fontSize,
                    onValueChange = { fontSize = it },
                    valueRange = 12f..96f,
                    steps = 0,
                )
            }
        },
        confirmButton = {
            TextButton(
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
                    )
                    onSubmit(item)
                },
            ) { Text("OK") }
        },
        dismissButton = {
            Row(modifier = Modifier.padding(end = 4.dp)) {
                if (initial != null && onDelete != null) {
                    TextButton(onClick = { onDelete(initial) }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
