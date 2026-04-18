package org.cobalt.internal.ui.util

import kotlin.math.max
import kotlin.math.min
import org.cobalt.api.util.ui.NVGRenderer

internal class TextInputHandler(
  initialText: String = "",
  private val maxLength: Int = Int.MAX_VALUE,
) {

  private var text: String = limitText(initialText)
  private var cursorPos = text.length
  private var selectionStart = -1
  private var selectionEnd = -1
  private var cursorBlinkTime = 0L
  private var textOffset = 0f

  fun getText(): String = text

  fun setText(value: String) {
    text = limitText(value)
    cursorPos = text.length
    clearSelection()
  }

  fun insertText(input: String) {
    if (hasSelection()) deleteSelection()

    val toInsert = input.take((maxLength - text.length).coerceAtLeast(0))
    if (toInsert.isEmpty()) return

    text = text.substring(0, cursorPos) + toInsert + text.substring(cursorPos)
    cursorPos += toInsert.length
    clearSelection()
    resetBlink()
  }

  fun backspace() {
    if (hasSelection()) {
      deleteSelection()
    } else if (cursorPos > 0) {
      text = text.substring(0, cursorPos - 1) + text.substring(cursorPos)
      cursorPos--
      resetBlink()
    }
  }

  fun delete() {
    if (hasSelection()) {
      deleteSelection()
    } else if (cursorPos < text.length) {
      text = text.substring(0, cursorPos) + text.substring(cursorPos + 1)
      resetBlink()
    }
  }

  fun moveCursorLeft(shift: Boolean) {
    if (shift) {
      if (!hasSelection()) selectionStart = cursorPos
      cursorPos = max(0, cursorPos - 1)
      selectionEnd = cursorPos
    } else {
      cursorPos = if (hasSelection()) min(selectionStart, selectionEnd) else max(0, cursorPos - 1)
      clearSelection()
    }
    resetBlink()
  }

  fun moveCursorRight(shift: Boolean) {
    if (shift) {
      if (!hasSelection()) selectionStart = cursorPos
      cursorPos = min(text.length, cursorPos + 1)
      selectionEnd = cursorPos
    } else {
      cursorPos = if (hasSelection()) max(selectionStart, selectionEnd) else min(text.length, cursorPos + 1)
      clearSelection()
    }
    resetBlink()
  }

  fun moveCursorToStart(shift: Boolean) {
    if (shift) {
      if (!hasSelection()) selectionStart = cursorPos
      selectionEnd = 0
    }
    cursorPos = 0
    if (!shift) clearSelection()
    resetBlink()
  }

  fun moveCursorToEnd(shift: Boolean) {
    if (shift) {
      if (!hasSelection()) selectionStart = cursorPos
      selectionEnd = text.length
    }
    cursorPos = text.length
    if (!shift) clearSelection()
    resetBlink()
  }

  fun selectAll() {
    selectionStart = 0
    selectionEnd = text.length
    cursorPos = text.length
  }

  fun copy(): String? = if (hasSelection()) {
    text.substring(min(selectionStart, selectionEnd), max(selectionStart, selectionEnd))
  } else null

  fun cut(): String? = copy()?.also { deleteSelection() }

  fun startSelection(x: Float, textX: Float, fontSize: Float) {
    cursorPos = getCursorPosFromX(x, textX, fontSize)
    selectionStart = cursorPos
    selectionEnd = cursorPos
    resetBlink()
  }

  fun updateSelection(x: Float, textX: Float, fontSize: Float) {
    cursorPos = getCursorPosFromX(x, textX, fontSize)
    selectionEnd = cursorPos
  }

  fun renderCursor(x: Float, y: Float, height: Float, color: Int) {
    normalizeState()
    if ((System.currentTimeMillis() - cursorBlinkTime) % 1000 < 500) {
      val cursorX = x + NVGRenderer.textWidth(textPrefix(cursorPos), height) - textOffset
      NVGRenderer.rect(cursorX, y, 1F, height, color)
    }
  }

  fun renderSelection(x: Float, y: Float, height: Float, fontSize: Float, color: Int) {
    if (!hasSelection()) return

    val start = min(selectionStart, selectionEnd)
    val end = max(selectionStart, selectionEnd)
    val startX = x + NVGRenderer.textWidth(textPrefix(start), fontSize) - textOffset
    val endX = x + NVGRenderer.textWidth(textPrefix(end), fontSize) - textOffset

    NVGRenderer.rect(startX, y, endX - startX, height, color)
  }

  fun updateScroll(viewWidth: Float, fontSize: Float) {
    normalizeState()
    val cursorX = NVGRenderer.textWidth(textPrefix(cursorPos), fontSize)
    val padding = 10f

    textOffset = when {
      cursorX - textOffset > viewWidth - padding -> cursorX - viewWidth + padding
      cursorX - textOffset < padding -> max(0f, cursorX - padding)
      else -> textOffset
    }
  }

  fun getTextOffset(): Float = textOffset

  private fun hasSelection(): Boolean {
    normalizeState()
    return selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd
  }

  private fun deleteSelection() {
    if (!hasSelection()) return

    val start = min(selectionStart, selectionEnd)
    val end = max(selectionStart, selectionEnd)

    text = text.substring(0, start) + text.substring(end)
    cursorPos = start
    clearSelection()
    resetBlink()
  }

  private fun clearSelection() {
    selectionStart = -1
    selectionEnd = -1
  }

  private fun limitText(value: String): String {
    return value.take(maxLength.coerceAtLeast(0))
  }

  private fun clampIndex(index: Int): Int {
    return index.coerceIn(0, text.length)
  }

  private fun normalizeState() {
    cursorPos = clampIndex(cursorPos)

    if (selectionStart < 0 || selectionEnd < 0) {
      clearSelection()
      return
    }

    selectionStart = clampIndex(selectionStart)
    selectionEnd = clampIndex(selectionEnd)
  }

  private fun textPrefix(index: Int): String {
    return text.substring(0, clampIndex(index))
  }

  private fun getCursorPosFromX(x: Float, textX: Float, fontSize: Float): Int {
    var closestPos = 0
    var closestDist = Float.MAX_VALUE

    for (i in 0..text.length) {
      val textWidth = NVGRenderer.textWidth(text.substring(0, i), fontSize)
      val dist = kotlin.math.abs(x - (textX + textWidth - textOffset))

      if (dist < closestDist) {
        closestDist = dist
        closestPos = i
      }
    }

    return closestPos
  }

  private fun resetBlink() {
    cursorBlinkTime = System.currentTimeMillis()
  }

}
