package me.timschneeberger.rootlessjamesdsp.utils.github

import java.io.IOException

/**
 * Everything that can go wrong talking to GitHub, one type per message the
 * user needs to see. A 403 is not always a rate limit, and a missing, empty
 * or blocked repository each need their own message.
 */
sealed class GithubException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** [bucket] is "core" (browsing) or "search"; they run out separately. */
    class RateLimited(val bucket: String, val resetAtMs: Long) :
        GithubException("GitHub $bucket limit reached until $resetAtMs")
    class NotFound : GithubException("Not found")
    class NoSuchBranch(val branch: String) : GithubException("No branch $branch")
    class NoSuchFolder(val folder: String) : GithubException("No folder $folder")
    class EmptyRepo : GithubException("Repository is empty")
    class Blocked : GithubException("Unavailable for legal reasons")
    class Refused : GithubException("Access refused")
    class Http(val code: Int) : GithubException("HTTP $code")
    class Offline(cause: Throwable) : GithubException("Network failure: ${cause.message}", cause)
    /** The bytes that arrived do not match the size or git hash the tree promised. */
    class Corrupt : GithubException("Downloaded file did not match")
    /** The real file is in Git LFS and GitHub would not serve it (usually the owner's bandwidth quota). */
    class LfsUnavailable(val code: Int) : GithubException("Git LFS file unavailable (HTTP $code)")
    class Cancelled : GithubException("Cancelled")
    /** A reply that could not be read: not the JSON or text GitHub normally sends. */
    class BadReply(cause: Throwable) : GithubException("Unreadable reply: ${cause.message}", cause)
}

object GithubErrors {
    const val CORE = "core"
    const val SEARCH = "search"

    /** When GitHub says it is limited but gives no time, wait this long. */
    private const val DEFAULT_WAIT_MS = 60_000L

    /**
     * Maps a non-success reply to the right exception.
     *
     * A rate limit is only a rate limit when GitHub says so: a 403 or 429
     * with X-RateLimit-Remaining: 0 (reset time in X-RateLimit-Reset, epoch
     * seconds) or a Retry-After (the secondary limit). A 403 without either
     * is a refusal, and saying "try again later" would never help.
     *
     * @param headers lower-cased header names
     */
    fun classify(code: Int, headers: Map<String, String>, bucket: String, nowMs: Long): GithubException {
        if (code == 403 || code == 429) {
            val which = headers["x-ratelimit-resource"]?.takeIf { it.isNotBlank() } ?: bucket
            val remaining = headers["x-ratelimit-remaining"]?.trim()
            val retryAfter = headers["retry-after"]?.trim()?.toLongOrNull()
            if (retryAfter != null)
                return GithubException.RateLimited(which, nowMs + retryAfter * 1000)
            if (remaining == "0") {
                val reset = headers["x-ratelimit-reset"]?.trim()?.toLongOrNull()
                val at = if (reset != null && reset * 1000 > nowMs) reset * 1000 else nowMs + DEFAULT_WAIT_MS
                return GithubException.RateLimited(which, at)
            }
            if (code == 429) return GithubException.RateLimited(which, nowMs + DEFAULT_WAIT_MS)
            return GithubException.Refused()
        }
        return when (code) {
            404 -> GithubException.NotFound()
            409 -> GithubException.EmptyRepo()
            451 -> GithubException.Blocked()
            else -> GithubException.Http(code)
        }
    }

    /** Anything thrown while talking to GitHub, as one of ours. */
    fun wrap(e: Throwable): GithubException = when (e) {
        is GithubException -> e
        is com.google.gson.stream.MalformedJsonException -> GithubException.BadReply(e)
        is IOException -> GithubException.Offline(e)
        else -> GithubException.BadReply(e)
    }
}
