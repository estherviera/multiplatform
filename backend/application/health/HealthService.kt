package com.nexora.backend.application.health

enum class HealthStatus(val wireValue: String) {
    OK("ok"),
    NOT_READY("not_ready"),
}

data class HealthCheckResult(
    val status: HealthStatus,
)

fun interface ReadinessCheck {
    suspend fun check(): HealthCheckResult
}

data class LivenessReport(
    val status: String,
)

data class ReadinessReport(
    val status: String,
    val service: String,
    val environment: String,
    val checks: Map<String, String>,
)

data class ReadinessEvaluation(
    val ready: Boolean,
    val report: ReadinessReport,
)

class HealthService(
    private val serviceName: String,
    private val environment: String,
    private val readinessChecks: Map<String, ReadinessCheck> = emptyMap(),
) {
    fun liveness(): LivenessReport = LivenessReport(status = HealthStatus.OK.wireValue)

    suspend fun readiness(): ReadinessEvaluation {
        val evaluated = linkedMapOf<String, String>()
        var allReady = true
        for ((name, check) in readinessChecks) {
            val result = try {
                check.check()
            } catch (_: Exception) {
                HealthCheckResult(HealthStatus.NOT_READY)
            }
            evaluated[name] = result.status.wireValue
            if (result.status != HealthStatus.OK) {
                allReady = false
            }
        }

        val overallStatus = if (allReady) "ready" else HealthStatus.NOT_READY.wireValue
        return ReadinessEvaluation(
            ready = allReady,
            report = ReadinessReport(
                status = overallStatus,
                service = serviceName,
                environment = environment,
                checks = evaluated,
            ),
        )
    }
}
