/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Horizontal tab bar identical-ish to Telegram's photo editor bottom bar — icon + label
 * stacked vertically, selected tab gets the accent colour and a tiny underline.
 *
 * Item is generic so the same component can drive Tune/Effects/Blur/Curves on photo
 * and Trim/Cover/Filters on video.
 */
@Composable
fun <T> TelegramTabBar(
    tabs: List<TabItem<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFF50A8EB),
) {
    val scroll = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEach { tab ->
            TabButton(
                tab = tab,
                isSelected = tab.value == selected,
                accentColor = accentColor,
                onClick = { onSelect(tab.value) },
            )
        }
    }
}

@Composable
private fun <T> TabButton(
    tab: TabItem<T>,
    isSelected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
) {
    val tint = if (isSelected) accentColor else Color.White.copy(alpha = 0.7f)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (tab.icon != null) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(4.dp))
        }
        BasicText(
            text = tab.label,
            style = TextStyle(
                color = tint,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
        Spacer(Modifier.height(3.dp))
        // Selected tab indicator — 2 dp accent line, hidden otherwise.
        Box(
            modifier = Modifier
                .height(2.dp)
                .width(20.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (isSelected) accentColor else Color.Transparent),
        )
    }
}

data class TabItem<T>(
    val value: T,
    val label: String,
    val icon: ImageVector? = null,
)
