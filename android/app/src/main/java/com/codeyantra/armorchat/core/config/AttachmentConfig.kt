package com.codeyantra.armorchat.core.config

object AttachmentConfig {
    const val MAX_IMAGE_SIZE_BYTES = 15 * 1024 * 1024L // 15 MB
    const val MAX_DOCUMENT_SIZE_BYTES = 25 * 1024 * 1024L // 25 MB
    const val BLOB_EXPIRY_DAYS = 7

    /**
     * Photos at or below this stay on the existing compress-and-inline path.
     * Larger photos are streamed as ciphertext chunks and are not recompressed.
     */
    const val LEGACY_IMAGE_COMPRESS_MAX_BYTES = 8L * 1024 * 1024

    /**
     * Plaintext at or below this, other than a legacy photo, keeps the existing
     * single-request blob PUT. Larger files use the chunked blob transport.
     */
    const val LEGACY_TRANSPORT_MAX_BYTES = 1L * 1024 * 1024

    /** Streaming transport cap. AES-GCM adds a 16-byte tag on top of this. */
    const val MAX_STREAM_BYTES = 100L * 1024 * 1024
}
