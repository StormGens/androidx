/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Providers
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width

/**
 * Opens a popup with the given content.
 *
 * The popup is positioned relative to its parent, using the [alignment] and [offset].
 * The popup is visible as long as it is part of the composition hierarchy.
 *
 * @sample androidx.compose.ui.samples.PopupSample
 *
 * @param alignment The alignment relative to the parent.
 * @param offset An offset from the original aligned position of the popup. Offset respects the
 * Ltr/Rtl context, thus in Ltr it will be added to the original aligned position and in Rtl it
 * will be subtracted from it.
 * @param isFocusable Indicates if the popup can grab the focus.
 * @param onDismissRequest Executes when the user clicks outside of the popup.
 * @param properties Typically platform specific properties to further configure the popup.
 * @param content The content to be displayed inside the popup.
 */
@Composable
fun Popup(
    alignment: Alignment = Alignment.TopStart,
    offset: IntOffset = IntOffset(0, 0),
    isFocusable: Boolean = false,
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties? = null,
    content: @Composable () -> Unit
) {
    val popupPositioner = remember(alignment, offset) {
        AlignmentOffsetPositionProvider(
            alignment,
            offset
        )
    }

    Popup(
        popupPositionProvider = popupPositioner,
        isFocusable = isFocusable,
        onDismissRequest = onDismissRequest,
        properties = properties,
        content = content
    )
}

/**
 * Common interface for popup properties. These are typically platform specific options to further
 * configure a popup. For android ones use AndroidPopupProperties.
 */
@Immutable
interface PopupProperties

/**
 * Opens a popup with the given content.
 *
 * The dropdown popup is positioned below its parent, using the [dropDownAlignment] and [offset].
 * The dropdown popup is visible as long as it is part of the composition hierarchy.
 *
 * @param dropDownAlignment The start or end alignment below the parent.
 * @param offset An offset from the original aligned position of the popup.
 * @param isFocusable Indicates if the popup can grab the focus.
 * @param onDismissRequest Executes when the user clicks outside of the popup.
 * @param content The content to be displayed inside the popup.
 */
@Composable
internal fun DropdownPopup(
    dropDownAlignment: DropDownAlignment = DropDownAlignment.Start,
    offset: IntOffset = IntOffset(0, 0),
    isFocusable: Boolean = false,
    onDismissRequest: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val popupPositioner = remember(dropDownAlignment, offset) {
        DropdownPositionProvider(
            dropDownAlignment,
            offset
        )
    }

    Popup(
        popupPositionProvider = popupPositioner,
        isFocusable = isFocusable,
        onDismissRequest = onDismissRequest,
        content = content
    )
}

// TODO(b/142431825): This is a hack to work around Popups not using Semantics for test tags
//  We should either remove it, or come up with an abstracted general solution that isn't specific
//  to Popup
internal val LocalPopupTestTag = compositionLocalOf { "DEFAULT_TEST_TAG" }

@Composable
internal fun PopupTestTag(tag: String, content: @Composable () -> Unit) {
    Providers(LocalPopupTestTag provides tag, content = content)
}

/**
 * Opens a popup with the given content.
 *
 * The popup is positioned based on the coordinates return from [popupPositionProvider].
 *
 * @param popupPositionProvider The position provider to be used to determine popup's position.
 * @param isFocusable Indicates if the popup can grab the focus.
 * @param onDismissRequest Executes when the user clicks outside of the popup.
 * @param properties Typically platform specific properties to further configure the popup.
 * @param content The content to be displayed inside the popup.
 */
@Composable
fun Popup(
    popupPositionProvider: PopupPositionProvider,
    isFocusable: Boolean = false,
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties? = null,
    content: @Composable () -> Unit
) = ActualPopup(
    popupPositionProvider,
    isFocusable,
    onDismissRequest,
    properties,
    content
)

@Composable
internal expect fun ActualPopup(
    popupPositionProvider: PopupPositionProvider,
    isFocusable: Boolean,
    onDismissRequest: (() -> Unit)?,
    properties: PopupProperties?,
    content: @Composable () -> Unit
)

/**
 * Calculates the position of a [Popup] on screen.
 */
@Immutable
interface PopupPositionProvider {
    /**
     * Calculates the position of a [Popup] on screen.
     *
     * The window size is useful in cases where the popup is meant to be positioned next to its
     * anchor instead of inside of it. The size can be used to calculate available space
     * around the parent to find a spot with enough clearance (e.g. when implementing a dropdown).
     * Note that positioning the popup outside of the window bounds might prevent it from being
     * visible.
     *
     * @param anchorBounds The window relative bounds of the layout which this popup is anchored to.
     * @param windowSize The size of the window containing the anchor layout.
     * @param layoutDirection The layout direction of the anchor layout.
     * @param popupContentSize The size of the popup's content.
     *
     * @return The window relative position where the popup should be positioned.
     */
    fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset
}

/**
 * The [DropdownPopup] is aligned below its parent relative to its left or right corner.
 * [DropDownAlignment] is used to specify how should [DropdownPopup] be aligned.
 */
internal enum class DropDownAlignment {
    Start,
    End
}

internal class AlignmentOffsetPositionProvider(
    val alignment: Alignment,
    val offset: IntOffset
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        // TODO: Decide which is the best way to round to result without reimplementing Alignment.align
        var popupPosition = IntOffset(0, 0)

        // Get the aligned point inside the parent
        val parentAlignmentPoint = alignment.align(
            IntSize.Zero,
            IntSize(anchorBounds.width, anchorBounds.height),
            layoutDirection
        )
        // Get the aligned point inside the child
        val relativePopupPos = alignment.align(
            IntSize.Zero,
            IntSize(popupContentSize.width, popupContentSize.height),
            layoutDirection
        )

        // Add the position of the parent
        popupPosition += IntOffset(anchorBounds.left, anchorBounds.top)

        // Add the distance between the parent's top left corner and the alignment point
        popupPosition += parentAlignmentPoint

        // Subtract the distance between the children's top left corner and the alignment point
        popupPosition -= IntOffset(relativePopupPos.x, relativePopupPos.y)

        // Add the user offset
        val resolvedOffset = IntOffset(
            offset.x * (if (layoutDirection == LayoutDirection.Ltr) 1 else -1),
            offset.y
        )
        popupPosition += resolvedOffset

        return popupPosition
    }
}

internal class DropdownPositionProvider(
    val dropDownAlignment: DropDownAlignment,
    val offset: IntOffset
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        var popupPosition = IntOffset(0, 0)

        // Add the position of the parent
        popupPosition += IntOffset(anchorBounds.left, anchorBounds.top)

        /*
        * In LTR context aligns popup's left edge with the parent's left edge for Start alignment
        * and parent's right edge for End alignment.
        * In RTL context aligns popup's right edge with the parent's right edge for Start alignment
        * and parent's left edge for End alignment.
        */
        val alignmentPositionX =
            if (dropDownAlignment == DropDownAlignment.Start) {
                if (layoutDirection == LayoutDirection.Ltr) {
                    0
                } else {
                    anchorBounds.width - popupContentSize.width
                }
            } else {
                if (layoutDirection == LayoutDirection.Ltr) {
                    anchorBounds.width
                } else {
                    -popupContentSize.width
                }
            }

        // The popup's position relative to the parent's top left corner
        val dropdownAlignmentPosition = IntOffset(alignmentPositionX, anchorBounds.height)

        popupPosition += dropdownAlignmentPosition

        // Add the user offset
        val resolvedOffset = IntOffset(
            offset.x * (if (layoutDirection == LayoutDirection.Ltr) 1 else -1),
            offset.y
        )
        popupPosition += resolvedOffset

        return popupPosition
    }
}
