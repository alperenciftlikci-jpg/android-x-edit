/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * `CANCEL  [icon] [icon] [icon]  DONE` row that lives at the bottom of every editor tab in
 * the Telegram-style photo editor (see screenshot reference: filter screen has CANCEL / 3
 * sub-icons / DONE; crop screen has CANCEL / nothing / CROP). Pass [centerContent] to fill
 * the middle slot with anything — sub-tab icons, segmented controls, blank.
 *
 * Visual matches Telegram exactly: 14 sp uppercase-ish labels, white left, accent right,
 * 56 dp tall row.
 */
@Composable
fun TelegramBottomActions(
    cancelLabel: String,
    doneLabel: String,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFF50A8EB),
    doneEnabled: Boolean = true,
    centerContent: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = cancelLabel,
            style = TextStyle(
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
            ),
            modifier = Modifier.clickable(onClick = onCancel).padding(8.dp),
        )
        Spacer(Modifier.weight(1f))
        Box(modifier = Modifier.weight(2f), contentAlignment = Alignment.Center) {
            centerContent()
        }
        Spacer(Modifier.weight(1f))
        BasicText(
            text = doneLabel,
            style = TextStyle(
                color = if (doneEnabled) accentColor else accentColor.copy(alpha = 0.4f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            ),
            modifier = Modifier.clickable(enabled = doneEnabled, onClick = onDone).padding(8.dp),
        )
    }
}

/**
 * Sub-tab icon button used inside [TelegramBottomActions]'s `centerContent`. Telegram's
 * filter screen uses three of these (filter / blur / curves) — selected one gets the accent
 * tint, others are 50% white.
 */
@Composable
fun TelegramSubTabIcon(
    icon: ImageVector,
    contentDescription: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    accentColor: Color = Color(0xFF50A8EB),
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isSelected) accentColor else Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Pill-shaped horizontal container for paint-tool buttons (color picker, brush types).
 * Telegram's draw screen wraps these in a rounded dark pill — we match the radius/padding.
 */
@Composable
fun TelegramToolPill(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF2A2A2A))
            .padding(horizontal = horizontalPadding, vertical = 6.dp),
    ) {
        content()
    }
}

/**
 * Pseudo-tab row matching the Telegram draw screen: `✕ DRAW STICKER TEXT ✓`. Selected tab is
 * white-bright + uppercase letterspaced, others are 50% white. Cancel/Done sit on the
 * outside edges and dispatch independent of tab selection.
 */
@Composable
fun TelegramSegmentedTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFF50A8EB),
) {
    Row(
        modifier = modifier.fillMaxWidth().height(48.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onCancel),
            contentAlignment = Alignment.Center) {
            BasicText(text = "✕",
                style = TextStyle(color = Color.White, fontSize = 18.sp))
        }
        tabs.forEachIndexed { i, label ->
            BasicText(
                text = label.uppercase(),
                style = TextStyle(
                    color = if (i == selectedIndex) Color.White
                            else Color.White.copy(alpha = 0.5f),
                    fontSize = 13.sp,
                    fontWeight = if (i == selectedIndex) FontWeight.SemiBold else FontWeight.Normal,
                    letterSpacing = 1.sp,
                ),
                modifier = Modifier.clickable { onSelectIndex(i) }.padding(8.dp),
            )
        }
        Box(modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onDone),
            contentAlignment = Alignment.Center) {
            BasicText(text = "✓",
                style = TextStyle(color = accentColor, fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold))
        }
    }
}
