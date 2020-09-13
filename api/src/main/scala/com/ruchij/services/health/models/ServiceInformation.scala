package com.ruchij.services.health.models

import cats.effect.{Clock, Sync}
import cats.implicits._
import com.eed3si9n.ruchij.api.BuildInfo
import com.ruchij.config.ApplicationInformation
import com.ruchij.types.JodaClock
import org.joda.time.DateTime

import scala.util.Properties

case class ServiceInformation(
  serviceName: String,
  serviceVersion: String,
  organization: String,
  scalaVersion: String,
  sbtVersion: String,
  javaVersion: String,
  currentTimestamp: DateTime,
  gitBranch: Option[String],
  gitCommit: Option[String],
  buildTimestamp: Option[DateTime]
)

object ServiceInformation {
  def create[F[_]: Sync: Clock](applicationInformation: ApplicationInformation): F[ServiceInformation] =
    for {
      timestamp <- JodaClock[F].timestamp
      javaVersion <- Sync[F].delay(Properties.javaVersion)

    } yield
      ServiceInformation(
        BuildInfo.name,
        BuildInfo.version,
        BuildInfo.organization,
        BuildInfo.scalaVersion,
        BuildInfo.sbtVersion,
        javaVersion,
        new DateTime(timestamp),
        applicationInformation.gitBranch,
        applicationInformation.gitCommit,
        applicationInformation.buildTimestamp
      )
}