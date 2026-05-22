package org.phantom.api.event.impl.client

import org.phantom.api.event.Event

/**
 * Posted from `FishingHookRenderer.shouldRender`. Cancel to hide the bobber.
 * [isLocalPlayerOwned] is true when the hook belongs to the local player.
 */
class FishingBobberRenderEvent(val isLocalPlayerOwned: Boolean) : Event(true)
