package dev.adminos.api.domain.common

open class RepositoryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class EntityNotFoundException(message: String) : RepositoryException(message)
class DuplicateEntityException(message: String) : RepositoryException(message)
