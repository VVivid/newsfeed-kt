package dev.vvivid.newsfeed.data

import java.io.IOException

/**
 * The server answered a request with an error the screens handle in their own way. Like any failed
 * request these are [IOException]s, so code that only needs to know "it failed" can ignore the
 * difference.
 */
sealed class NewsServerException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * HTTP 404: the article is not in the view that was asked for. It may have moved into or out of
 * Trash, which the server only searches when asked to.
 */
class ArticleNotFoundException : NewsServerException("The article was not found")

/**
 * HTTP 409: the article changed after the app read it, so the revision the app sent is stale. The
 * app reloads it instead of overwriting the newer state.
 */
class ArticleConflictException : NewsServerException("The article changed on the server")

/** HTTP 403: the sign-in does not include the permission this request needs. */
class PermissionDeniedException : NewsServerException("The sign-in does not allow this request")

/** Any other answer the app cannot use: another status, or a body that is not what the API defines. */
class UnexpectedResponseException(
    message: String,
    cause: Throwable? = null,
) : NewsServerException(message, cause)

/**
 * Whether the server refused a state change because the article is no longer as the app last saw
 * it: its revision changed (409), or it moved into or out of Trash, so it is not where it was (404).
 * The app then reloads the article instead of retrying the change.
 */
fun Throwable?.isArticleChangedOnServer(): Boolean = this is ArticleConflictException || this is ArticleNotFoundException
