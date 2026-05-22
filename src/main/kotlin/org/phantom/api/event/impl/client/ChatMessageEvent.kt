package org.phantom.api.event.impl.client

import org.phantom.api.event.Event

/** Posted when a message is added to the chat HUD; [message] is plain text. */
class ChatMessageEvent(val message: String) : Event()
