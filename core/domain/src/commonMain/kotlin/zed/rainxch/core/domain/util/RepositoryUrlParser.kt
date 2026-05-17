package zed.rainxch.core.domain.util

import zed.rainxch.core.domain.model.RepositoryReference
import zed.rainxch.core.domain.model.RepositorySource

object RepositoryUrlParser {
    private val urlRegex = Regex(
        """^(?:https?://)?([^/\s]+)/([^/\s]+)/([^/\s?#]+)(?:[/?#].*)?$""",
        RegexOption.IGNORE_CASE,
    )

    private val knownForgejoHosts = setOf(
        "codeberg.org",
        "git.disroot.org",
        "gitea.com",
    )

    fun parse(rawUrl: String): RepositoryReference? {
        val trimmed = rawUrl.trim().trimEnd('/')
        val match = urlRegex.matchEntire(trimmed) ?: return null
        val host = match.groupValues[1].lowercase()
        val owner = match.groupValues[2]
        val repo = match.groupValues[3].removeSuffix(".git")
        if (owner.isEmpty() || repo.isEmpty()) return null

        val source = when {
            host == "github.com" || host == "www.github.com" -> RepositorySource.GitHub
            host in knownForgejoHosts -> RepositorySource.Forgejo(host)
            looksLikeForgejoHost(host) -> RepositorySource.Forgejo(host)
            else -> return null
        }
        return RepositoryReference(source, owner, repo)
    }

    private fun looksLikeForgejoHost(host: String): Boolean {
        val lower = host.lowercase()
        return lower.contains("forgejo") || lower.contains("gitea")
    }
}
