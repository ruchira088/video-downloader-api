package com.ruchij.core.services.scheduling

import cats.effect.{IO, Resource, Timer}
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.core.messaging.inmemory.Fs2PubSub
import com.ruchij.core.messaging.kafka.KafkaSubscriber.CommittableRecord
import com.ruchij.core.services.download.Http4sDownloadService
import com.ruchij.core.services.hashing.MurmurHash3Service
import com.ruchij.core.services.repository.InMemoryRepositoryService
import com.ruchij.core.services.scheduling.models.DownloadProgress
import com.ruchij.core.services.video.VideoAnalysisServiceImpl
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.Providers.{blocker, contextShift}
import com.ruchij.core.test.{DoobieProvider, Providers}
import doobie.ConnectionIO
import fs2.Stream
import org.http4s._
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.headers.{`Content-Length`, `Content-Type`}
import org.http4s.implicits._
import org.joda.time.DateTime
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

class SchedulingServiceImplSpec extends AnyFlatSpec with Matchers with MockFactory with OptionValues {
  import ExecutionContext.Implicits.global

  "SchedulingServiceImpl" should "save the scheduled video task" in runIO {
    val videoUrl: Uri = uri"https://www.pornone.com/caught/caught-my-bbc-roommate-spying/276979928/"

    val dateTime = DateTime.now()
    implicit val timer: Timer[IO] = Providers.stubTimer(dateTime)

    val client =
      Client[IO] { request =>
        Resource.eval {
          HttpRoutes
            .of[IO] {
              case GET -> Root / "caught" / "caught-my-bbc-roommate-spying" / "276979928" / "" =>
                Ok {
                  {
                    <div>
                      <div class="single-video">
                        <div id="video_player">
                          <div class="video-player-head">
                            <h1>Caught My Bbc Roommate Spying</h1>
                          </div>
                          <video poster="https://th-eu3.pornone.com/t/b81.jpg"></video>
                          <source src="https://s279.pornone.com/vid2/276979928_1920x1080_4000k.mp4"/>
                        </div>
                        <div id="video-info">
                          <div class="video-duration">
                            3 min 24 sec
                          </div>
                        </div>
                      </div>

                    </div>
                  }
                    .toString()
                }

              case HEAD -> Root / "vid2" / "276979928_1920x1080_4000k.mp4" =>
                IO.pure {
                  Response[IO](
                    status = Status.Ok,
                    headers = Headers.of(`Content-Length`.unsafeFromLong(1988))
                  )
                }

              case GET -> Root / "t" / "b81.jpg" =>
                IO.pure {
                  Response[IO](
                    status = Status.Ok,
                    headers =
                      Headers.of(
                        `Content-Length`.unsafeFromLong(100),
                        `Content-Type`(MediaType.image.jpeg)
                      ),
                    body = Stream[IO, Byte](1).repeat.take(100)
                  )
                }
            }
            .orNotFound
            .run(request)
        }
      }

    val storageConfiguration = new StorageConfiguration { override val imageFolder: String = "/images" }
    val hashingService = new MurmurHash3Service[IO](blocker)
    val repositoryService = new InMemoryRepositoryService[IO](new ConcurrentHashMap())
    val downloadService = new Http4sDownloadService[IO](client, repositoryService)

    DoobieProvider.inMemoryTransactor[IO]
      .use { implicit transaction =>
        for {
          videoAnalysisService <- IO.pure {
            new VideoAnalysisServiceImpl[IO, ConnectionIO](
              hashingService,
              downloadService,
              client,
              DoobieVideoMetadataDao,
              DoobieFileResourceDao,
              storageConfiguration
            )
          }

          videoId <- hashingService.hash(videoUrl.renderString).map(hash => s"pornone-$hash")
          fileId <-
            hashingService.hash(uri"https://th-eu3.pornone.com/t/b81.jpg".renderString).map(hash => s"$videoId-$hash")

          expectedScheduledDownloadVideo =
            ScheduledVideoDownload(
              dateTime,
              dateTime,
              SchedulingStatus.Queued,
              0,
              VideoMetadata(
                videoUrl,
                videoId,
                VideoSite.PornOne,
                "Caught My Bbc Roommate Spying",
                204 seconds,
                1988,
                FileResource(
                  fileId,
                  dateTime,
                  s"${storageConfiguration.imageFolder}/thumbnail-$videoId-b81.jpg",
                  MediaType.image.jpeg,
                  100
                )
              ),
              None
            )

          downloadProgressPubSub <- Fs2PubSub[IO, DownloadProgress]
          scheduledVideoDownloadUpdatesPubSub <- Fs2PubSub[IO, ScheduledVideoDownload]

          schedulingService =
            new SchedulingServiceImpl[IO, ConnectionIO](
              videoAnalysisService,
              DoobieSchedulingDao,
              downloadProgressPubSub,
              scheduledVideoDownloadUpdatesPubSub
            )

          scheduledVideoDownload <- schedulingService.schedule(videoUrl)
          _ = scheduledVideoDownload mustBe expectedScheduledDownloadVideo

          receivedMessages <-
            scheduledVideoDownloadUpdatesPubSub.subscribe("SchedulingServiceImplSpec")
              .take(1)
              .compile
              .toList
              .map {
                _.map { case CommittableRecord(message, _) => message }
              }

          _ = receivedMessages.headOption mustBe Some(expectedScheduledDownloadVideo)
        }
        yield (): Unit
      }
  }
}