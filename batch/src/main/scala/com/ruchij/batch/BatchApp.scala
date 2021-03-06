package com.ruchij.batch

import cats.effect.{Blocker, ConcurrentEffect, ContextShift, ExitCode, IO, IOApp, Resource, Sync, Timer}
import cats.implicits._
import com.ruchij.batch.config.BatchServiceConfiguration
import com.ruchij.batch.services.enrichment.VideoEnrichmentServiceImpl
import com.ruchij.batch.services.scheduler.{Scheduler, SchedulerImpl}
import com.ruchij.batch.services.sync.SynchronizationServiceImpl
import com.ruchij.batch.services.worker.WorkExecutorImpl
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.daos.snapshot.DoobieSnapshotDao
import com.ruchij.core.daos.video.DoobieVideoDao
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.daos.workers.DoobieWorkerDao
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.kafka.{KafkaPubSub, KafkaSubscriber}
import com.ruchij.core.messaging.models.HttpMetric
import com.ruchij.core.services.download.Http4sDownloadService
import com.ruchij.core.services.hashing.MurmurHash3Service
import com.ruchij.core.services.repository.{FileRepositoryService, PathFileTypeDetector}
import com.ruchij.core.services.scheduling.SchedulingServiceImpl
import com.ruchij.core.services.scheduling.models.DownloadProgress
import com.ruchij.core.services.video.{VideoAnalysisServiceImpl, VideoServiceImpl}
import com.ruchij.migration.MigrationApp
import doobie.free.connection.ConnectionIO
import org.apache.tika.Tika
import org.http4s.client.asynchttpclient.AsyncHttpClient
import org.http4s.client.middleware.FollowRedirect
import pureconfig.ConfigSource

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object BatchApp extends IOApp {

  private val logger = Logger[IO, BatchApp.type]

  override def run(args: List[String]): IO[ExitCode] =
    for {
      configObjectSource <- IO.delay(ConfigSource.defaultApplication)
      batchServiceConfiguration <- BatchServiceConfiguration.parse[IO](configObjectSource)

      _ <- program[IO](batchServiceConfiguration)
        .use { scheduler =>
          scheduler.init
            .productR(logger.infoF("Scheduler has started"))
            .productR {
              scheduler.run
                .evalMap { video =>
                  logger.infoF(s"Download completed for videoId = ${video.videoMetadata.id}")
                }
                .compile
                .drain
            }
        }
    } yield ExitCode.Success

  def program[F[+ _]: ConcurrentEffect: ContextShift: Timer](
    batchServiceConfiguration: BatchServiceConfiguration
  ): Resource[F, Scheduler[F]] =
    DoobieTransactor.create[F](batchServiceConfiguration.databaseConfiguration)
      .map(_.trans)
      .flatMap { implicit transaction =>
        for {
          httpClient <-
            AsyncHttpClient.resource { AsyncHttpClient.configure(_.setRequestTimeout((24 hours).toMillis.toInt)) }
              .map(FollowRedirect(maxRedirects = 10))

          ioThreadPool <-
            Resource.make(Sync[F].delay(Executors.newCachedThreadPool())) { executorService =>
              Sync[F].delay(executorService.shutdown())
            }
          ioBlocker = Blocker.liftExecutionContext(ExecutionContext.fromExecutor(ioThreadPool))

          processorCount <- Resource.eval(Sync[F].delay(Runtime.getRuntime.availableProcessors()))
          cpuBlockingThreadPool <-
            Resource.make(Sync[F].delay(Executors.newFixedThreadPool(processorCount))) { executorService =>
              Sync[F].delay(executorService.shutdown())
            }
          cpuBlocker = Blocker.liftExecutionContext(ExecutionContext.fromExecutor(cpuBlockingThreadPool))

          _ <- Resource.eval(MigrationApp.migration[F](batchServiceConfiguration.databaseConfiguration, ioBlocker))

          workerDao = new DoobieWorkerDao(DoobieSchedulingDao)

          repositoryService = new FileRepositoryService[F](ioBlocker)
          downloadService = new Http4sDownloadService[F](httpClient, repositoryService)
          hashingService = new MurmurHash3Service[F](cpuBlocker)
          videoAnalysisService = new VideoAnalysisServiceImpl[F, ConnectionIO](
            hashingService,
            downloadService,
            httpClient,
            DoobieVideoMetadataDao,
            DoobieFileResourceDao,
            batchServiceConfiguration.storageConfiguration
          )

          downloadProgressPubSub <- KafkaPubSub[F, DownloadProgress](batchServiceConfiguration.kafkaConfiguration)
          scheduledVideoDownloadPubSub <- KafkaPubSub[F, ScheduledVideoDownload](batchServiceConfiguration.kafkaConfiguration)
          httpMetricsSubscriber = new KafkaSubscriber[F, HttpMetric](batchServiceConfiguration.kafkaConfiguration)

          schedulingService = new SchedulingServiceImpl[F, ConnectionIO](
            videoAnalysisService,
            DoobieSchedulingDao,
            downloadProgressPubSub,
            scheduledVideoDownloadPubSub
          )

          fileTypeDetector = new PathFileTypeDetector[F](new Tika(), ioBlocker)

          videoService = new VideoServiceImpl[F, ConnectionIO](
            repositoryService,
            DoobieVideoDao,
            DoobieVideoMetadataDao,
            DoobieSnapshotDao,
            DoobieSchedulingDao,
            DoobieFileResourceDao
          )

          videoEnrichmentService = new VideoEnrichmentServiceImpl[F, repositoryService.BackedType, ConnectionIO](
            repositoryService,
            hashingService,
            DoobieSnapshotDao,
            DoobieFileResourceDao,
            ioBlocker,
            batchServiceConfiguration.storageConfiguration
          )

          synchronizationService = new SynchronizationServiceImpl[F, repositoryService.BackedType, ConnectionIO](
            repositoryService,
            DoobieFileResourceDao,
            DoobieVideoMetadataDao,
            videoService,
            videoEnrichmentService,
            hashingService,
            fileTypeDetector,
            ioBlocker,
            batchServiceConfiguration.storageConfiguration
          )

          workExecutor = new WorkExecutorImpl[F, ConnectionIO](
            DoobieFileResourceDao,
            workerDao,
            repositoryService,
            schedulingService,
            videoAnalysisService,
            videoService,
            hashingService,
            downloadService,
            videoEnrichmentService,
            batchServiceConfiguration.storageConfiguration
          )

          scheduler = new SchedulerImpl(
            schedulingService,
            synchronizationService,
            videoService,
            workExecutor,
            httpMetricsSubscriber,
            workerDao,
            batchServiceConfiguration.workerConfiguration,
            batchServiceConfiguration.applicationInformation.instanceId
          )
        } yield scheduler
      }
}
