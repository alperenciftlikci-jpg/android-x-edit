/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.element.android.libraries.imageeditor.native_.BlurType
import io.element.android.libraries.imageeditor.native_.Brush
import io.element.android.libraries.imageeditor.native_.BrushType
import io.element.android.libraries.imageeditor.native_.CropParams
import io.element.android.libraries.imageeditor.native_.CurveLut
import io.element.android.libraries.imageeditor.native_.FilterParams

/**
 * State holder for the Telegram-style photo editor screen. Holds the full editor state
 * (filter params + crop + paint + text) so each tab can mutate its slice without disturbing
 * the others, and the screen can run a single coalesced live preview render whenever any
 * piece of state changes.
 */
@Stable
class PhotoEditorProState {

    // ---- Filter ----
    var params: FilterParams by mutableStateOf(FilterParams())
        private set

    // ---- Crop ----
    var crop: CropParams by mutableStateOf(CropParams())
        private set
    /** Optional aspect ratio lock for the crop rect (null = free). Telegram default is "Free".
     *  Ratio is expressed in source-image pixels — "1:1" means a square in the photo, not a
     *  square in normalised crop coords. */
    var cropAspectLocked: Float? by mutableStateOf(null)

    /** Source bitmap dimensions, populated by the screen on source load. Used so
     *  [setCropAspect] can convert image-pixel ratios into normalised rect dimensions. */
    var sourceWidth: Int by mutableStateOf(0)
        internal set
    var sourceHeight: Int by mutableStateOf(0)
        internal set

    // ---- Paint ----
    // Smaller default — Telegram-style. Stroke radius is in source-pixel space, so on a 1080p
    // photo this lands around 1% of width, which is roughly what Telegram ships. Image-aware
    // scaling kicks in once the screen knows the source dimensions (see [scaleBrushToSource]).
    var brush: Brush by mutableStateOf(Brush(type = BrushType.Pen, radiusPx = 6f))
        private set

    /** Re-scale the default brush radius to make on-screen thickness consistent regardless of
     *  source bitmap dimensions. Telegram does the same — a 4K photo gets a thicker stamp than
     *  a 600 px thumbnail so the user-perceived stroke matches. Caller should invoke once on
     *  source load (see PhotoEditorProScreen). No-ops if the user has already moved the size
     *  slider away from the initial default. */
    fun scaleBrushToSource(srcWidth: Int, srcHeight: Int) {
        if (brush.radiusPx != 6f) return
        val minDim = minOf(srcWidth, srcHeight).coerceAtLeast(1)
        val target = (minDim / 200f).coerceIn(2f, 18f)
        brush = brush.copy(radiusPx = target)
    }
    val paintStrokesCommitted: SnapshotStateList<Long> = mutableStateListOf()  // colour-brush stroke ids — drives Paint-tab undo + preview re-render
    val blurStrokesCommitted: SnapshotStateList<Long> = mutableStateListOf()   // BlurBrush stroke ids — independent stack for Blur-tab undo
    /** Bumped on every paint-stroke pointer sample. Driver for live preview re-renders during
     *  drawing; the screen's snapshotFlow watches this so the user sees their stroke build up
     *  on-canvas in real time, not only after lifting the finger. */
    var paintStrokeTick: Long by mutableStateOf(0L)
    /** Bumped after any text item is added / removed / edited. Same purpose as
     *  [paintStrokeTick] but for the text composite — without it, typing into a text item
     *  re-renders the bitmap natively but the preview snapshot wouldn't fire and you'd
     *  type into a void until the next slider drag forced a re-render. */
    var textTick: Long by mutableStateOf(0L)

    // ---- Text ----
    val textItems: SnapshotStateList<TextItemEdit> = mutableStateListOf()
    var nextTextId: Int = 1
        private set
    var editingTextId: Int? by mutableStateOf(null)
    /** True when the selected text item is in "type" mode — IME is open, keystrokes go in.
     *  False = item is selected but movable / scalable only (Telegram calls this state the
     *  selection-only mode; `editText.setEnabled(false)`). */
    var isTextEditing: Boolean by mutableStateOf(false)

    // ---- Tab ----
    // Nullable: the editor opens with NO tool selected so the user picks one explicitly,
    // and committing a tool returns to this null state (Telegram-style "fresh slate" between
    // tool sessions, but more explicit — Telegram always defaults to Enhance).
    var selectedTab: Tab? by mutableStateOf(null)

    fun setParams(update: (FilterParams) -> FilterParams) {
        params = update(params)
    }

    // Filter setters
    fun setExposure(v: Float)   { params = params.copy(exposure = v) }
    fun setBrightness(v: Float) { params = params.copy(brightness = v) }
    fun setContrast(v: Float)   { params = params.copy(contrast = v) }
    fun setSaturation(v: Float) { params = params.copy(saturation = v) }
    fun setWarmth(v: Float)     { params = params.copy(warmth = v) }
    fun setFade(v: Float)       { params = params.copy(fade = v) }
    fun setHighlights(v: Float) { params = params.copy(highlights = v) }
    fun setShadows(v: Float)    { params = params.copy(shadows = v) }
    fun setVignette(v: Float)   { params = params.copy(vignette = v) }
    fun setGrain(v: Float)      { params = params.copy(grain = v) }
    fun setSharpen(v: Float)    { params = params.copy(sharpen = v) }

    // Blur setters
    fun setBlurType(type: BlurType)            { params = params.copy(blur = params.blur.copy(type = type)) }
    fun setBlurCenter(x: Float, y: Float)      { params = params.copy(blur = params.blur.copy(centerX = x, centerY = y)) }
    fun setBlurInnerRadius(r: Float)           { params = params.copy(blur = params.blur.copy(innerRadius = r)) }
    fun setBlurOuterRadius(r: Float)           { params = params.copy(blur = params.blur.copy(outerRadius = r)) }
    fun setBlurAngle(rad: Float)               { params = params.copy(blur = params.blur.copy(angleRadians = rad)) }
    fun setBlurStrength(v: Float)              { params = params.copy(blur = params.blur.copy(strength = v)) }

    // Curves
    fun setCurves(lut: CurveLut) { params = params.copy(curves = lut) }

    // Crop setters
    fun setCropRect(x: Float, y: Float, w: Float, h: Float) {
        crop = crop.copy(x = x, y = y, w = w, h = h)
    }
    fun setCropAspect(aspect: Float?) {
        cropAspectLocked = aspect
        if (aspect == null || aspect <= 0f) return
        // Telegram-style: ratio refers to SOURCE PIXELS, not normalised coords. So "1:1"
        // gives a square crop in the image, which translates to a *non-square* normalised
        // rect when the source isn't square. Without using the source aspect here the rect
        // would lie about its on-screen shape — tapping 1:1 on a landscape photo previously
        // produced a full-width rect that wasn't 1:1 at all.
        val srcW = sourceWidth.coerceAtLeast(1).toFloat()
        val srcH = sourceHeight.coerceAtLeast(1).toFloat()
        val imageAspect = srcW / srcH
        // Solve for normalised w, h such that (w * srcW) / (h * srcH) = aspect and the
        // larger axis fills [0, 1]. So normalisedRatio = aspect / imageAspect.
        val nRatio = aspect / imageAspect
        val newW: Float
        val newH: Float
        if (nRatio >= 1f) {
            // Crop is wider than tall in normalised coords → width is the limit at 1.
            newW = 1f
            newH = 1f / nRatio
        } else {
            newH = 1f
            newW = nRatio
        }
        val cx = crop.x + crop.w / 2f
        val cy = crop.y + crop.h / 2f
        crop = crop.copy(
            x = (cx - newW / 2f).coerceIn(0f, 1f - newW),
            y = (cy - newH / 2f).coerceIn(0f, 1f - newH),
            w = newW,
            h = newH,
        )
    }
    fun rotate90()       { crop = crop.copy(rotation90 = (crop.rotation90 + 1) % 4) }
    fun setFreeAngle(deg: Float) { crop = crop.copy(freeAngle = deg.coerceIn(-45f, 45f)) }
    fun toggleMirrorH()  { crop = crop.copy(mirrorH = !crop.mirrorH) }
    fun toggleMirrorV()  { crop = crop.copy(mirrorV = !crop.mirrorV) }
    fun resetCrop()      { crop = CropParams(); cropAspectLocked = null }

    // Paint setters
    fun setBrushType(type: BrushType) { brush = brush.copy(type = type) }
    fun setBrushColor(r: Float, g: Float, b: Float, a: Float = 1f) {
        brush = brush.copy(r = r, g = g, b = b, a = a)
    }
    fun setBrushSize(px: Float)        { brush = brush.copy(radiusPx = px) }

    // Text setters
    fun beginNewTextItem(): Int {
        val id = nextTextId++
        textItems.add(TextItemEdit(id = id))
        editingTextId = id
        return id
    }
    fun updateTextItem(id: Int, transform: TextItemEdit.() -> TextItemEdit) {
        val idx = textItems.indexOfFirst { it.id == id }
        if (idx >= 0) textItems[idx] = textItems[idx].transform()
    }
    fun removeTextItem(id: Int) {
        textItems.removeAll { it.id == id }
        if (editingTextId == id) editingTextId = null
    }
    fun finishEditingText() { editingTextId = null }

    fun reset() {
        params = FilterParams()
        crop = CropParams()
        cropAspectLocked = null
        textItems.clear()
        paintStrokesCommitted.clear()
        blurStrokesCommitted.clear()
        editingTextId = null
    }

    enum class Tab { Tune, Effects, Blur, Crop, Paint, Text }
}

/**
 * One text-overlay item managed by the editor screen. Position/size/rotation are stored in
 * normalised image-space; `bitmapDirty` flips true whenever the rendered content changes
 * (text body, font size, colour) so the screen knows to re-render the bitmap and re-upload
 * to the native TextLayer.
 */
/** Visual frame variants matching Telegram's TextPaintView types. */
enum class TextFrameType {
    Plain,       // type 3 — coloured text, no background
    Solid,       // type 0 — solid colour fill, contrasting text inside
    Semi,        // type 1 — semi-transparent background fill, swatch-colour text
    Outline,     // type 2 — thin outline frame, swatch-colour text
}

data class TextItemEdit(
    val id: Int,
    val text: String = "",
    val fontSizePx: Float = 64f,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val centerX: Float = 0.5f,         // normalised image-space
    val centerY: Float = 0.5f,
    val scale: Float = 1f,
    val rotationRad: Float = 0f,
    val frameType: TextFrameType = TextFrameType.Plain,
)

@Composable
fun rememberPhotoEditorProState(): PhotoEditorProState =
    remember { PhotoEditorProState() }
