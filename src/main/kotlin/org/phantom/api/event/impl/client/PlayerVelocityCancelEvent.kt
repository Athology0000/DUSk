package org.phantom.api.event.impl.client

import org.phantom.api.event.Event

/** Query: cancelled means horizontal velocity should be zeroed this tick. */
class PlayerVelocityCancelEvent : Event(true)
