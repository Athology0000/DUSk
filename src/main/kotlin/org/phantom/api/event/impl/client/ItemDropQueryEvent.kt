package org.phantom.api.event.impl.client

import org.phantom.api.event.Event

/** Query: cancelled means the selected-item drop should be prevented. */
class ItemDropQueryEvent : Event(true)
