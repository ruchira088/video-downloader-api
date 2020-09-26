package com.ruchij.batch.services.scheduling

import java.util.concurrent.ConcurrentHashMap

import cats.effect.{Async, ContextShift, IO, Resource, Timer}
import cats.implicits._
import com.ruchij.batch.daos.resource.models.FileResource
import com.ruchij.batch.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.batch.services.download.{DownloadService, Http4sDownloadService}
import com.ruchij.batch.services.hashing.{HashingService, MurmurHash3Service}
import com.ruchij.batch.services.repository.InMemoryRepositoryService
import com.ruchij.batch.services.video.VideoAnalysisService
import com.ruchij.batch.services.video.models.VideoAnalysisResult
import com.ruchij.daos.videometadata.models.VideoMetadata
import com.ruchij.services.download.Http4sDownloadService
import com.ruchij.services.hashing.HashingService
import SchedulingServiceImplSpec.{createSchedulingService, downloadConfiguration}
import com.ruchij.batch.daos.resource.DoobieFileResourceDao
import com.ruchij.batch.daos.scheduling.DoobieSchedulingDao
import com.ruchij.batch.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.batch.test.utils.Providers
import Providers.{blocker, contextShift}
import com.ruchij.batch.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.batch.types.FunctionKTypes
import doobie.ConnectionIO
import fs2.Stream
import org.http4s.client.Client
import org.http4s.headers.{`Content-Length`, `Content-Type`}
import org.http4s.implicits._
import org.http4s.{MediaType, Response, Uri}
import org.joda.time.DateTime
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

class SchedulingServiceImplSpec extends AnyFlatSpec with Matchers with MockFactory with OptionValues {
  import ExecutionContext.Implicits.global

  "SchedulingServiceImpl" should "save the scheduled video task" in {
    val videoUrl: Uri = uri"https://www.vporn.com/caught/caught-my-bbc-roommate-spying/276979928/"

    val videoAnalysisService = mock[VideoAnalysisService[IO]]

    val videoAnalysisResult =
      VideoAnalysisResult(
        videoUrl,
        VideoSite.PornOne,
        "Caught My Bbc Roommate Spying",
        204 seconds,
        1988,
        uri"https://th-eu3.vporn.com/t/28/276979928/b81.jpg"
      )

    (videoAnalysisService.metadata _)
      .expects(videoUrl)
      .returns(IO.pure(videoAnalysisResult))

    val dateTime = DateTime.now()
    implicit val timer: Timer[IO] = Providers.stubTimer(dateTime)

    val client =
      Client[IO] {
        request =>
          Resource.liftF(IO.delay(request.uri mustBe videoAnalysisResult.thumbnail))
            .productR {
              Resource.pure[IO, Response[IO]] {
                Response[IO]()
                  .withHeaders(
                    `Content-Length`.unsafeFromLong(videoAnalysisResult.size),
                    `Content-Type`(MediaType.image.jpeg)
                  )
                  .withBodyStream(Stream.emits[IO, Byte](Seq.fill(videoAnalysisResult.size.toInt)(1)))
              }
            }
      }

    val hashingService = new MurmurHash3Service[IO](blocker)
    val repositoryService = new InMemoryRepositoryService[IO](new ConcurrentHashMap())
    val downloadService = new Http4sDownloadService[IO](client, repositoryService)

    val videoId = hashingService.hash(videoUrl.renderString).unsafeRunSync()

    val expectedScheduledDownloadVideo =
      ScheduledVideoDownload(
        dateTime,
        dateTime,
        None,
        VideoMetadata(
          videoAnalysisResult.url,
          videoId,
          videoAnalysisResult.videoSite,
          videoAnalysisResult.title,
          videoAnalysisResult.duration,
          videoAnalysisResult.size,
          FileResource(
            hashingService.hash(videoAnalysisResult.thumbnail.renderString).unsafeRunSync(),
            dateTime,
            s"${downloadConfiguration.imageFolder}/thumbnail-$videoId-b81.jpg",
            MediaType.image.jpeg,
            videoAnalysisResult.size
          )
        ),
        0,
        None
      )

    val insertionResult =
      for {
        schedulingService <- createSchedulingService[IO](videoAnalysisService, hashingService, downloadService)
        scheduledVideoDownload <- schedulingService.schedule(videoUrl)
      } yield scheduledVideoDownload

    insertionResult.unsafeRunSync() mustBe expectedScheduledDownloadVideo
  }
}

object SchedulingServiceImplSpec {
  val downloadConfiguration: DownloadConfiguration =
    DownloadConfiguration(videoFolder = "videos", imageFolder = "images")

  def createSchedulingService[F[_]: Async: ContextShift: Timer](
    videoAnalysisService: VideoAnalysisService[F],
    hashingService: HashingService[F],
    downloadService: DownloadService[F]
  ): F[SchedulingService[F]] =
    Providers.h2Transactor
      .map(FunctionKTypes.transaction[F])
      .map { implicit transaction =>
        new SchedulingServiceImpl[F, ConnectionIO](
          videoAnalysisService,
          DoobieSchedulingDao,
          DoobieVideoMetadataDao,
          DoobieFileResourceDao,
          hashingService,
          downloadService,
          downloadConfiguration
        )
      }
}