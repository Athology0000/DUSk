package org.cobalt.internal.ui.panel.panels

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.account.AccountManagerService
import org.cobalt.internal.ui.UIComponent
import org.cobalt.internal.ui.components.UITopbar
import org.cobalt.internal.ui.panel.UIPanel
import org.cobalt.internal.ui.util.ScrollHandler
import org.cobalt.internal.ui.util.TextInputHandler
import org.cobalt.internal.ui.util.isHoveringOver
import org.cobalt.internal.ui.util.mouseX
import org.lwjgl.glfw.GLFW

internal class UIAccountManagerPanel : UIPanel(
  x = 0F,
  y = 0F,
  width = 890F,
  height = 600F,
) {

  private val topBar = UITopbar("Accounts")
  private val aliasInput = UITextField("Microsoft alias...", 280F)
  private val loginButton = UIActionButton(
    label = "Add / Login",
    width = 130F,
  ) {
    val alias = aliasInput.getText().trim()
    if (alias.isNotEmpty()) {
      AccountManagerService.login(alias)
      aliasInput.clear()
    }
  }

  private val scrollHandler = ScrollHandler()
  private var filterText = ""
  private var visibleEntries: List<UIAccountEntry> = emptyList()

  init {
    components.add(topBar)
    components.add(aliasInput)
    components.add(loginButton)

    topBar.searchChanged { filter ->
      filterText = filter.trim()
    }
  }

  override fun render() {
    val theme = ThemeManager.currentTheme
    NVGRenderer.rect(x, y, width, height, theme.background, 10F)

    topBar
      .updateBounds(x, y)
      .render()

    renderSessionCard()

    val inputY = y + topBar.height + 106F
    aliasInput
      .updateBounds(x + 24F, inputY)
      .render()

    loginButton
      .setEnabled(!AccountManagerService.isBusy())
      .updateBounds(x + 24F + aliasInput.width + 12F, inputY)
      .render()

    NVGRenderer.text(
      AccountManagerService.getStatusMessage(),
      x + 24F,
      y + topBar.height + 160F,
      14F,
      theme.textSecondary,
    )

    val accounts = filteredAccounts()
    val listY = y + topBar.height + 190F
    val visibleHeight = height - (listY - y) - 16F
    scrollHandler.setMaxScroll(accounts.size * (ENTRY_HEIGHT + ENTRY_GAP) + 8F, visibleHeight)

    NVGRenderer.pushScissor(x + 16F, listY, width - 32F, visibleHeight)

    val scrollOffset = scrollHandler.getOffset()
    visibleEntries = accounts.mapIndexed { index, account ->
      UIAccountEntry(account).apply {
        updateBounds(
          x + 20F,
          listY + 8F + index * (ENTRY_HEIGHT + ENTRY_GAP) - scrollOffset,
        )
      }
    }

    if (visibleEntries.isEmpty()) {
      NVGRenderer.text(
        if (filterText.isBlank()) "No stored accounts yet." else "No accounts match \"$filterText\".",
        x + 28F,
        listY + 18F,
        15F,
        theme.textSecondary,
      )
    } else {
      visibleEntries.forEach { entry ->
        if (entry.y + entry.height >= listY - ENTRY_GAP && entry.y <= listY + visibleHeight + ENTRY_GAP) {
          entry.render()
        }
      }
    }

    NVGRenderer.popScissor()
  }

  override fun mouseClicked(button: Int): Boolean {
    return visibleEntries.any { it.mouseClicked(button) } ||
      loginButton.mouseClicked(button) ||
      aliasInput.mouseClicked(button) ||
      topBar.mouseClicked(button)
  }

  override fun mouseReleased(button: Int): Boolean {
    return aliasInput.mouseReleased(button) || topBar.mouseReleased(button)
  }

  override fun mouseDragged(button: Int, offsetX: Double, offsetY: Double): Boolean {
    return aliasInput.mouseDragged(button, offsetX, offsetY) || topBar.mouseDragged(button, offsetX, offsetY)
  }

  override fun charTyped(input: CharacterEvent): Boolean {
    return aliasInput.charTyped(input) || topBar.charTyped(input)
  }

  override fun keyPressed(input: KeyEvent): Boolean {
    if (input.key == GLFW.GLFW_KEY_ENTER && aliasInput.getText().isNotBlank() && !AccountManagerService.isBusy()) {
      AccountManagerService.login(aliasInput.getText().trim())
      aliasInput.clear()
      return true
    }

    return aliasInput.keyPressed(input) || topBar.keyPressed(input)
  }

  override fun mouseScrolled(horizontalAmount: Double, verticalAmount: Double): Boolean {
    if (isHoveringOver(x, y + topBar.height + 180F, width, height - topBar.height - 180F)) {
      scrollHandler.handleScroll(verticalAmount)
      return true
    }

    return false
  }

  private fun renderSessionCard() {
    val theme = ThemeManager.currentTheme
    val cardX = x + 20F
    val cardY = y + topBar.height + 18F
    val cardWidth = width - 40F
    val cardHeight = 72F

    NVGRenderer.rect(cardX, cardY, cardWidth, cardHeight, theme.panel, 10F)
    NVGRenderer.hollowRect(cardX, cardY, cardWidth, cardHeight, 1F, theme.controlBorder, 10F)

    NVGRenderer.text("Current Session", cardX + 16F, cardY + 14F, 16F, theme.text)
    NVGRenderer.text(AccountManagerService.getCurrentSessionName(), cardX + 16F, cardY + 38F, 20F, theme.accent)
    NVGRenderer.text(
      AccountManagerService.getCurrentSessionUuid(),
      cardX + 16F,
      cardY + 56F,
      12F,
      theme.textSecondary,
    )
  }

  private fun filteredAccounts(): List<AccountManagerService.ManagedAccount> {
    val query = filterText.lowercase()
    return AccountManagerService.getAccounts().filter { account ->
      query.isBlank() ||
        account.alias.lowercase().contains(query) ||
        account.minecraftName.lowercase().contains(query)
    }
  }

  private class UIAccountEntry(
    private val account: AccountManagerService.ManagedAccount,
  ) : UIComponent(
    x = 0F,
    y = 0F,
    width = 850F,
    height = ENTRY_HEIGHT,
  ) {

    private val useButton = UIActionButton("Use", 74F) {
      AccountManagerService.login(account.alias)
    }

    private val removeButton = UIActionButton("Remove", 86F, destructive = true) {
      AccountManagerService.remove(account.alias)
    }

    override fun render() {
      val theme = ThemeManager.currentTheme
      val current = AccountManagerService.isCurrentAccount(account)
      val busy = AccountManagerService.isBusy()

      NVGRenderer.rect(x, y, width, height, theme.panel, 10F)
      NVGRenderer.hollowRect(
        x,
        y,
        width,
        height,
        if (current) 2F else 1F,
        if (current) theme.accent else theme.controlBorder,
        10F,
      )

      NVGRenderer.text(account.alias, x + 16F, y + 16F, 18F, theme.text)

      val profileText =
        if (account.minecraftName.isBlank()) "No completed login yet"
        else "Last profile: ${account.minecraftName}"
      NVGRenderer.text(profileText, x + 16F, y + 40F, 14F, theme.textSecondary)

      val lastLoginText =
        if (account.lastLoginAt <= 0L) "Last login: never"
        else "Last login: ${DATE_FORMAT.format(Instant.ofEpochMilli(account.lastLoginAt))}"
      NVGRenderer.text(lastLoginText, x + 16F, y + 60F, 12F, theme.textSecondary)

      if (current) {
        val pillWidth = 72F
        NVGRenderer.rect(x + width - 220F, y + 14F, pillWidth, 24F, theme.accent, 8F)
        NVGRenderer.text("Current", x + width - 220F + 12F, y + 20F, 13F, theme.textOnAccent)
      }

      useButton
        .setEnabled(!busy)
        .updateBounds(x + width - 182F, y + height - 40F)
        .render()

      removeButton
        .setEnabled(!busy)
        .updateBounds(x + width - 96F, y + height - 40F)
        .render()
    }

    override fun mouseClicked(button: Int): Boolean {
      return useButton.mouseClicked(button) || removeButton.mouseClicked(button)
    }
  }

  private class UIActionButton(
    private val label: String,
    width: Float,
    private val destructive: Boolean = false,
    private val onClick: () -> Unit,
  ) : UIComponent(
    x = 0F,
    y = 0F,
    width = width,
    height = 30F,
  ) {

    private var enabled = true

    fun setEnabled(value: Boolean): UIActionButton {
      enabled = value
      return this
    }

    override fun render() {
      val theme = ThemeManager.currentTheme
      val hovering = enabled && isHoveringOver(x, y, width, height)
      val background = when {
        !enabled -> theme.controlBg
        destructive -> if (hovering) 0xFF9A3D3D.toInt() else 0xFF6D2C2C.toInt()
        hovering -> theme.accentSecondary
        else -> theme.controlBg
      }
      val border = when {
        !enabled -> theme.controlBorder
        destructive -> 0xFFB05757.toInt()
        hovering -> theme.accent
        else -> theme.controlBorder
      }
      val textColor = if (enabled && (hovering || destructive)) theme.textOnAccent else theme.text

      NVGRenderer.rect(x, y, width, height, background, 8F)
      NVGRenderer.hollowRect(x, y, width, height, 1F, border, 8F)
      NVGRenderer.text(
        label,
        x + width / 2F - NVGRenderer.textWidth(label, 13F) / 2F,
        y + 9F,
        13F,
        textColor,
      )
    }

    override fun mouseClicked(button: Int): Boolean {
      if (!enabled || button != 0 || !isHoveringOver(x, y, width, height)) return false
      onClick.invoke()
      return true
    }
  }

  private class UITextField(
    private val placeholder: String,
    width: Float,
  ) : UIComponent(
    x = 0F,
    y = 0F,
    width = width,
    height = 36F,
  ) {

    private val inputHandler = TextInputHandler("", 64)
    private var focused = false
    private var dragging = false

    fun getText(): String = inputHandler.getText()

    fun clear() {
      inputHandler.setText("")
      focused = false
    }

    override fun render() {
      val theme = ThemeManager.currentTheme
      val borderColor = if (focused) theme.accent else theme.controlBorder

      NVGRenderer.rect(x, y, width, height, theme.inputBg, 6F)
      NVGRenderer.hollowRect(x, y, width, height, 1.5F, borderColor, 6F)

      val textX = x + 12F
      val textY = y + 12F

      if (focused) inputHandler.updateScroll(width - 24F, 13F)

      NVGRenderer.pushScissor(x + 10F, y + 5F, width - 20F, height - 10F)

      if (focused) {
        inputHandler.renderSelection(textX, textY, 13F, 13F, theme.selection)
      }

      val text = inputHandler.getText()
      if (text.isEmpty() && !focused) {
        NVGRenderer.text(placeholder, textX, textY, 13F, theme.searchPlaceholderText)
      } else {
        NVGRenderer.text(text, textX - inputHandler.getTextOffset(), textY, 13F, theme.text)
      }

      if (focused) {
        inputHandler.renderCursor(textX, textY, 13F, theme.text)
      }

      NVGRenderer.popScissor()
    }

    override fun mouseClicked(button: Int): Boolean {
      if (button != 0) return false

      if (isHoveringOver(x, y, width, height)) {
        focused = true
        dragging = true
        inputHandler.startSelection(mouseX.toFloat(), x + 12F, 13F)
        return true
      }

      if (focused) {
        focused = false
        return true
      }

      return false
    }

    override fun mouseReleased(button: Int): Boolean {
      if (button == 0) dragging = false
      return false
    }

    override fun mouseDragged(button: Int, offsetX: Double, offsetY: Double): Boolean {
      if (button == 0 && dragging && focused) {
        inputHandler.updateSelection(mouseX.toFloat(), x + 12F, 13F)
        return true
      }
      return false
    }

    override fun charTyped(input: CharacterEvent): Boolean {
      if (!focused) return false

      val char = input.codepoint.toChar()
      if (char.code >= 32 && char != '\u007f') {
        inputHandler.insertText(char.toString())
        return true
      }

      return false
    }

    override fun keyPressed(input: KeyEvent): Boolean {
      if (!focused) return false

      val ctrl = input.modifiers and GLFW.GLFW_MOD_CONTROL != 0
      val shift = input.modifiers and GLFW.GLFW_MOD_SHIFT != 0

      when (input.key) {
        GLFW.GLFW_KEY_ESCAPE -> {
          focused = false
          return true
        }

        GLFW.GLFW_KEY_BACKSPACE -> {
          inputHandler.backspace()
          return true
        }

        GLFW.GLFW_KEY_DELETE -> {
          inputHandler.delete()
          return true
        }

        GLFW.GLFW_KEY_LEFT -> {
          inputHandler.moveCursorLeft(shift)
          return true
        }

        GLFW.GLFW_KEY_RIGHT -> {
          inputHandler.moveCursorRight(shift)
          return true
        }

        GLFW.GLFW_KEY_HOME -> {
          inputHandler.moveCursorToStart(shift)
          return true
        }

        GLFW.GLFW_KEY_END -> {
          inputHandler.moveCursorToEnd(shift)
          return true
        }

        GLFW.GLFW_KEY_A -> if (ctrl) {
          inputHandler.selectAll()
          return true
        }
      }

      return false
    }
  }

  companion object {
    private const val ENTRY_HEIGHT = 88F
    private const val ENTRY_GAP = 12F
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
  }
}
