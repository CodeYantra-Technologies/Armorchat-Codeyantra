package com.codeyantra.armorchat.core.network

enum class RelayRole {
    BOOTSTRAP,
    AUTHORIZED,
    /** Reserved: community/federation nodes are not implemented. */
    COMMUNITY,
}

data class RelayNode(
    val id: String,
    val origin: String,
    val role: RelayRole,
) {
    val canonicalOrigin: String = CodeYantraRelayTransport.parseOrigin(origin)
        .newBuilder()
        .encodedPath("/")
        .query(null)
        .fragment(null)
        .build()
        .toString()
        .trimEnd('/')
}

/**
 * Catalog of CodeYantra-authorized relay nodes. The first deployed node is
 * only the default bootstrap (Node-1), never a permanent global singleton.
 *
 * Community/federation relays stay listed as a future role and are rejected
 * for official-client traffic until that feature is implemented.
 */
interface RelayRegistry {
    fun bootstrap(): RelayNode
    fun allAuthorized(): List<RelayNode>
    fun findById(id: String): RelayNode?
    fun findByOrigin(origin: String): RelayNode?
    fun isAuthorizedOrigin(origin: String): Boolean
}

object CodeYantraRelays {
    const val NODE_1_ID = "node-1"
    const val NODE_1_ORIGIN = "https://relay1.codeyantra.in"
    const val STAGING_ID = "node-staging"
    const val STAGING_ORIGIN = "https://relay-staging.139.59.68.42.sslip.io"

    fun node1Bootstrap(): RelayNode =
        RelayNode(id = NODE_1_ID, origin = NODE_1_ORIGIN, role = RelayRole.BOOTSTRAP)

    fun stagingBootstrap(): RelayNode =
        RelayNode(id = STAGING_ID, origin = STAGING_ORIGIN, role = RelayRole.BOOTSTRAP)
}

class DefaultRelayRegistry(
    nodes: List<RelayNode> = listOf(CodeYantraRelays.node1Bootstrap()),
) : RelayRegistry {
    private val authorized: List<RelayNode> = nodes.map { node ->
        require(node.role != RelayRole.COMMUNITY) {
            "community relays are not enabled"
        }
        RelayNode(node.id, node.canonicalOrigin, node.role)
    }

    init {
        require(authorized.isNotEmpty()) { "relay registry requires at least one node" }
        require(authorized.any { it.role == RelayRole.BOOTSTRAP }) {
            "relay registry requires a bootstrap node"
        }
        require(authorized.map { it.id }.toSet().size == authorized.size) { "duplicate relay id" }
        require(authorized.map { it.canonicalOrigin }.toSet().size == authorized.size) {
            "duplicate relay origin"
        }
    }

    override fun bootstrap(): RelayNode =
        authorized.first { it.role == RelayRole.BOOTSTRAP }

    override fun allAuthorized(): List<RelayNode> = authorized

    override fun findById(id: String): RelayNode? = authorized.find { it.id == id }

    override fun findByOrigin(origin: String): RelayNode? {
        val canonical = canonicalOrigin(origin)
        return authorized.find { it.canonicalOrigin == canonical }
    }

    override fun isAuthorizedOrigin(origin: String): Boolean = findByOrigin(origin) != null

    private fun canonicalOrigin(origin: String): String =
        CodeYantraRelayTransport.parseOrigin(origin)
            .newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')
}

interface RelayProvider {
    fun bootstrapClient(): RelayClient
    fun clientFor(origin: String): RelayClient
}

class DefaultRelayProvider(
    private val registry: RelayRegistry,
    private val factory: (String) -> RelayClient,
) : RelayProvider {
    override fun bootstrapClient(): RelayClient = clientFor(registry.bootstrap().origin)

    override fun clientFor(origin: String): RelayClient {
        val node = registry.findByOrigin(origin)
            ?: throw IllegalArgumentException("relay origin is not in the authorized registry")
        return factory(node.canonicalOrigin)
    }
}
