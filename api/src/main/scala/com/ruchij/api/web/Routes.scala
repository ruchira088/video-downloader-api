package com.ruchij.api.web

import cats.effect.{Blocker, Concurrent, ContextShift, Timer}
import cats.implicits._
import com.ruchij.api.services.authentication.AuthenticationService
import com.ruchij.api.services.health.HealthService
import com.ruchij.api.web.middleware.{Authenticator, ExceptionHandler, MetricsMiddleware, NotFoundHandler}
import com.ruchij.api.web.routes._
import com.ruchij.core.messaging.Publisher
import com.ruchij.core.messaging.models.HttpMetric
import com.ruchij.core.services.asset.AssetService
import com.ruchij.core.services.scheduling.SchedulingService
import com.ruchij.core.services.scheduling.models.DownloadProgress
import com.ruchij.core.services.video.{VideoAnalysisService, VideoService}
import fs2.Stream
import org.http4s.dsl.Http4sDsl
import org.http4s.server.middleware.{CORS, GZip}
import org.http4s.server.{HttpMiddleware, Router}
import org.http4s.{HttpApp, HttpRoutes}

object Routes {
  def apply[F[+ _]: Concurrent: Timer: ContextShift](
    videoService: VideoService[F],
    videoAnalysisService: VideoAnalysisService[F],
    schedulingService: SchedulingService[F],
    assetService: AssetService[F],
    healthService: HealthService[F],
    authenticationService: AuthenticationService[F],
    downloadProgressStream: Stream[F, DownloadProgress],
    metricPublisher: Publisher[F, HttpMetric],
    ioBlocker: Blocker
  ): HttpApp[F] = {
    implicit val dsl: Http4sDsl[F] = new Http4sDsl[F] {}

    val authMiddleware: HttpMiddleware[F] =
      if (authenticationService.enabled) Authenticator.middleware[F](authenticationService, strict = true) else identity

    val routes: HttpRoutes[F] =
      WebServerRoutes(ioBlocker) <+>
        Router(
          "/authentication" -> AuthenticationRoutes(authenticationService),
          "/schedule" -> authMiddleware(SchedulingRoutes(schedulingService, downloadProgressStream)),
          "/videos" -> authMiddleware(VideoRoutes(videoService, videoAnalysisService)),
          "/assets" -> authMiddleware(AssetRoutes(assetService)),
          "/service" -> ServiceRoutes(healthService),
        )

    MetricsMiddleware(metricPublisher) {
      GZip {
        CORS {
          ExceptionHandler {
            NotFoundHandler(routes)
          }
        }
      }
    }
  }
}
