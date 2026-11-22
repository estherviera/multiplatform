package com.nexora.backend.adapter.http.ktor

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.nexora.backend.application.multitenant.DomainConflictException
import com.nexora.backend.application.multitenant.DomainValidationException
import com.nexora.backend.application.multitenant.ForbiddenOperationException
import com.nexora.backend.application.multitenant.ProfileIdentityConflictException
import com.nexora.backend.application.multitenant.ResourceNotFoundException
import com.nexora.backend.application.persistence.MissingIdentityContextException
import org.postgresql.util.PSQLException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.slf4j.LoggerFactory

data class FieldError(
    val field: String,
    val reason: String,
)

data class ErrorDetail(
    val code: String,
    val message: String,
    val requestId: String?,
    val details: List<FieldError>? = null,
)

data class ApiErrorResponse(
    val error: ErrorDetail,
)

sealed class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val details: List<FieldError>? = null,
) : RuntimeException(message)

class BadRequestApiException(
    code: String = "bad_request",
    message: String = "Invalid request",
    details: List<FieldError>? = null,
) : ApiException(HttpStatusCode.BadRequest, code, message, details)

class UnauthorizedApiException(
    code: String = "unauthorized",
    message: String = "Authentication required",
) : ApiException(HttpStatusCode.Unauthorized, code, message)

class ForbiddenApiException(
    code: String = "forbidden",
    message: String = "Access denied",
) : ApiException(HttpStatusCode.Forbidden, code, message)

class NotFoundApiException(
    code: String = "not_found",
    message: String = "Resource not found",
) : ApiException(HttpStatusCode.NotFound, code, message)

class ConflictApiException(
    code: String = "conflict",
    message: String = "Resource conflict",
) : ApiException(HttpStatusCode.Conflict, code, message)

class PayloadTooLargeApiException(
    code: String = "payload_too_large",
    message: String = "Payload too large",
) : ApiException(HttpStatusCode.PayloadTooLarge, code, message)

class UnsupportedMediaTypeApiException(
    code: String = "unsupported_media_type",
    message: String = "Unsupported media type",
) : ApiException(HttpStatusCode.UnsupportedMediaType, code, message)

private const val DEFAULT_MAX_JSON_BODY_BYTES = 16_384

private val strictJsonBodyMapper: ObjectMapper = ObjectMapper()
    .registerModule(KotlinModule.Builder().build())
    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

internal suspend fun <T> ApplicationCall.receiveBoundedJson(
    targetType: Class<T>,
    maxBytes: Int = DEFAULT_MAX_JSON_BODY_BYTES,
): T {
    if (!request.contentType().withoutParameters().match(ContentType.Application.Json)) {
        throw UnsupportedMediaTypeApiException()
    }
    val declaredLength = request.contentLength()
    if (declaredLength != null && declaredLength > maxBytes) {
        throw PayloadTooLargeApiException(
            code = "payload_too_large",
            message = "Request body exceeds maximum allowed size",
        )
    }
    val channel = receiveChannel()
    val out = ByteArrayOutputStream()
    val buf = ByteArray(2048)
    var total = 0
    while (!channel.isClosedForRead) {
        val read = channel.readAvailable(buf, 0, buf.size)
        if (read == -1) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) {
            throw PayloadTooLargeApiException(
                code = "payload_too_large",
                message = "Request body exceeds maximum allowed size",
            )
        }
        out.write(buf, 0, read)
    }
    val payload = String(out.toByteArray(), StandardCharsets.UTF_8)
    if (payload.isBlank()) {
        throw BadRequestApiException(code = "invalid_json", message = "Malformed JSON payload")
    }
    return strictJsonBodyMapper.readValue(payload, targetType)
}

class ServiceUnavailableApiException(
    code: String = "service_unavailable",
    message: String = "Service temporarily unavailable",
) : ApiException(HttpStatusCode.ServiceUnavailable, code, message)

private val errorLogger = LoggerFactory.getLogger("com.nexora.backend.adapter.http.ktor.ErrorHandling")

fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(
                cause.status,
                ApiErrorResponse(
                    error = ErrorDetail(
                        code = cause.code,
                        message = cause.message,
                        requestId = call.callId,
                        details = cause.details?.takeIf { it.isNotEmpty() },
                    ),
                ),
            )
        }

        exception<DomainValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorResponse(
                    error = ErrorDetail(
                        code = "validation_failed",
                        message = cause.message ?: "Validation failed",
                        requestId = call.callId,
                        details = cause.violations.map { FieldError(it.field, it.reason) },
                    ),
                ),
            )
        }

        exception<ResourceNotFoundException> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                ApiErrorResponse(
                    error = ErrorDetail(
                        code = cause.code,
                        message = cause.message,
                        requestId = call.callId,
                    ),
                ),
            )
        }

        exception<ForbiddenOperationException> { call, cause ->
            call.respond(
                HttpStatusCode.Forbidden,
                ApiErrorResponse(
                    error = ErrorDetail(
                        code = cause.code,
                        message = cause.message,
                        requestId = call.callId,
                    ),
                ),
            )
        }

        exception<DomainConflictException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiErrorResponse(
                    error = ErrorDetail(
                        code = cause.code,
                        message = cause.message,
                        requestId = call.callId,
                    ),
                ),
            )
        }

        exception<PSQLException> { call, cause ->
            val serverMessage = cause.serverErrorMessage?.message
            if (cause.sqlState == "23514" && serverMessage?.contains("active company_admin", ignoreCase = true) == true) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ApiErrorResponse(
                        error = ErrorDetail(
                            code = "last_active_admin_required",
                            message = "Active company must retain at least one active administrator",
                            requestId = call.callId,
                        ),
                    ),
                )
            } else {
                errorLogger.error(
                    "Unhandled database error [requestId={}, method={}, path={}, sqlState={}]",
                    call.callId,
                    call.request.httpMethod.value,
                    call.request.path(),
                    cause.sqlState,
                )
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiErrorResponse(
                        error = ErrorDetail(
                            code = "internal_error",
                            message = "Internal server error",
                            requestId = call.callId,
                        ),
                    ),
                )
            }
        }

        exception<ProfileIdentityConflictException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiErrorResponse(
                    error = ErrorDetail(
                        code = "profile_identity_conflict",
                        message = cause.message,
                        requestId = call.callId,
                    ),
                ),
            )
        }

        exception<MissingIdentityContextException> { call, _ ->
            call.respond(
                HttpStatusCode.Unauthorized,
                ApiErrorResponse(
                    error = ErrorDetail(
                        code = "missing_identity_context",
                        message = "Authenticated identity is required",
                        requestId = call.callId,
                    ),
                ),
            )
        }

        exception<BadRequestException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorResponse(
                    error = ErrorDetail(
                        code = "invalid_request",
                        message = "Malformed or invalid request payload",
                        requestId = call.callId,
                    ),
                ),
            )
        }

        exception<ContentTransformationException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorResponse(
                    error = ErrorDetail(
                        code = "invalid_request",
                        message = "Malformed or invalid request payload",
                        requestId = call.callId,
                    ),
                ),
            )
        }

        exception<JsonProcessingException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorResponse(
                    error = ErrorDetail(
                        code = "invalid_json",
                        message = "Malformed JSON payload",
                        requestId = call.callId,
                    ),
                ),
            )
        }

        exception<Throwable> { call, cause ->
            errorLogger.error(
                "Unhandled error handling request [requestId={}, method={}, path={}, exception={}, location={}]",
                call.callId,
                call.request.httpMethod.value,
                call.request.path(),
                exceptionTypeChain(cause),
                topOriginFrame(cause),
            )
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiErrorResponse(
                    error = ErrorDetail(
                        code = "internal_error",
                        message = "Internal server error",
                        requestId = call.callId,
                    ),
                ),
            )
        }

        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(
                status,
                ApiErrorResponse(
                    error = ErrorDetail(
                        code = "not_found",
                        message = "Resource not found",
                        requestId = call.callId,
                    ),
                ),
            )
        }

        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respond(
                status,
                ApiErrorResponse(
                    error = ErrorDetail(
                        code = "method_not_allowed",
                        message = "Method not allowed",
                        requestId = call.callId,
                    ),
                ),
            )
        }

        status(HttpStatusCode.UnsupportedMediaType) { call, status ->
            call.respond(
                status,
                ApiErrorResponse(
                    error = ErrorDetail(
                        code = "unsupported_media_type",
                        message = "Unsupported media type",
                        requestId = call.callId,
                    ),
                ),
            )
        }
    }
}

private fun exceptionTypeChain(cause: Throwable, maxDepth: Int = 5): String {
    val types = mutableListOf<String>()
    val visited = mutableSetOf<Throwable>()
    var current: Throwable? = cause
    while (current != null && types.size < maxDepth && visited.add(current)) {
        types.add(current::class.java.name)
        current = current.cause
    }
    return types.joinToString(" -> ")
}

private fun topOriginFrame(cause: Throwable): String {
    val frame = cause.stackTrace.firstOrNull { it.className.startsWith("com.nexora.") }
        ?: cause.stackTrace.firstOrNull()
        ?: return "unknown"
    return "${frame.className}.${frame.methodName}:${frame.lineNumber}"
}
