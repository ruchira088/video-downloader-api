package com.ruchij.api.services.health.models

import shapeless.Generic

case class HealthCheck(
  database: HealthStatus,
  fileRepository: HealthStatus,
  keyValueStore: HealthStatus,
  pubSubStatus: HealthStatus
) { self =>
  val isHealthy: Boolean =
    Generic[HealthCheck].to(self).toList.forall(_ == HealthStatus.Healthy)
}