package org.phantom.api.event.impl.client

import org.phantom.api.event.Event

/** Query: cancelled means the backward-movement key should read as pressed. */
class ForcedBackwardKeyEvent : Event(true)
