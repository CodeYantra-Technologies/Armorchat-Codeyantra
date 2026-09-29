package com.codeyantra.armorchat.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Opaque sealed mesh bundle. Deadline is absolute and is not refreshed on a hop. */
@Entity(
    tableName = "mesh_custody",
    indices = [Index(value = ["expireAtMs"])],
)
data class MeshCustodyEntity(
    @PrimaryKey val id: String,
    val createdAtMs: Long,
    val expireAtMs: Long,
    val hopCount: Int,
    val emergency: Boolean,
    val payload: ByteArray,
    val forwardedCount: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshCustodyEntity) return false
        return id == other.id && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = id.hashCode()
}
