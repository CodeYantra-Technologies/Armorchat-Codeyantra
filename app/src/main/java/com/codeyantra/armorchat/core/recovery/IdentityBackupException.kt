package com.codeyantra.armorchat.core.recovery

/**
 * Typed failures for user-controlled encrypted identity backup files.
 */
sealed class IdentityBackupException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    class InvalidPassword(
        message: String = "The backup password is incorrect or the backup file was tampered with.",
        cause: Throwable? = null,
    ) : IdentityBackupException(message, cause)

    class UnsupportedFormat(
        message: String = "This ArmorChat backup format is not supported.",
        cause: Throwable? = null,
    ) : IdentityBackupException(message, cause)

    class Corrupted(
        message: String = "The ArmorChat backup file is missing data or corrupted.",
        cause: Throwable? = null,
    ) : IdentityBackupException(message, cause)
}
