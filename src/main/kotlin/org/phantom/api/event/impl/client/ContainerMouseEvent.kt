package org.phantom.api.event.impl.client

import org.phantom.api.event.Event

/** Posted on `AbstractContainerScreen` mouse interactions. Cancel to swallow. */
class ContainerMouseEvent(val kind: Kind) : Event(true) {
  enum class Kind { CLICK, RELEASE, DRAG, SCROLL }
}
