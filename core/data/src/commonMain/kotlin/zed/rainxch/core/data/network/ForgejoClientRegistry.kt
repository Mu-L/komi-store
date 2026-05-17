package zed.rainxch.core.data.network

class ForgejoClientRegistry {
    private val clients = mutableMapOf<String, ForgejoApiClient>()

    fun clientFor(host: String): ForgejoApiClient {
        val key = host.lowercase().trim()
        return clients.getOrPut(key) { ForgejoApiClient(key) }
    }
}
