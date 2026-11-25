package dev.vvivid.newsfeed.data

import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [block] and returns its result, or null if it failed. Cancellation is not a failure: it
 * propagates, so work stopped because a screen closed or a newer request replaced it never turns
 * into an error message.
 */
internal inline fun <T : Any> resultOrNull(block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
