package com.ruchij.services.scheduling

import java.util.concurrent.TimeUnit

import cats.data.{NonEmptyList, OptionT}
import cats.effect.{Sync, Timer}
import cats.implicits._
import cats.{ApplicativeError, Monad, ~>}
import com.ruchij.config.DownloadConfiguration
import com.ruchij.daos.resource.FileResourceDao
import com.ruchij.daos.resource.models.FileResource
import com.ruchij.daos.scheduling.SchedulingDao
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.daos.videometadata.VideoMetadataDao
import com.ruchij.daos.videometadata.models.VideoMetadata
import com.ruchij.exceptions.{InvalidConditionException, ResourceNotFoundException}
import com.ruchij.kv.KeySpacedKeyValueStore
import com.ruchij.kv.keys.KVStoreKey.DownloadProgressKey
import com.ruchij.services.download.DownloadService
import com.ruchij.services.hashing.HashingService
import com.ruchij.services.models.{Order, SortBy}
import com.ruchij.services.scheduling.models.DownloadProgress
import com.ruchij.services.video.VideoAnalysisService
import com.ruchij.services.video.models.VideoAnalysisResult
import com.ruchij.types.JodaClock
import fs2.Stream
import org.http4s.Uri
import org.joda.time.DateTime

import scala.concurrent.duration.Duration

class SchedulingServiceImpl[F[_]: Sync: Timer, T[_]: Monad](
  videoAnalysisService: VideoAnalysisService[F],
  schedulingDao: SchedulingDao[T],
  videoMetadataDao: VideoMetadataDao[T],
  fileResourceDao: FileResourceDao[T],
  keySpacedKeyValueStore: KeySpacedKeyValueStore[F, DownloadProgressKey, DownloadProgress],
  hashingService: HashingService[F],
  downloadService: DownloadService[F],
  downloadConfiguration: DownloadConfiguration
)(implicit transaction: T ~> F)
    extends SchedulingService[F] {

  override def schedule(uri: Uri): F[ScheduledVideoDownload] =
    for {
      VideoAnalysisResult(_, videoSite, title, duration, size, thumbnailUri) <- videoAnalysisService.metadata(uri)
      timestamp <- JodaClock[F].timestamp

      videoId <- hashingService.hash(uri.renderString)

      thumbnailFileName = thumbnailUri.path.split("/").lastOption.getOrElse("thumbnail.unknown")
      filePath = s"${downloadConfiguration.imageFolder}/thumbnail-$videoId-$thumbnailFileName"

      thumbnail <- downloadService
        .download(thumbnailUri, filePath)
        .use { downloadResult =>
          downloadResult.data.compile.drain
            .productR(hashingService.hash(thumbnailUri.renderString))
            .map { fileId =>
              FileResource(
                fileId,
                timestamp,
                downloadResult.downloadedFileKey,
                downloadResult.mediaType,
                downloadResult.size
              )
            }
        }

      videoMetadata = VideoMetadata(uri, videoId, videoSite, title, duration, size, thumbnail)

      scheduledVideoDownload = ScheduledVideoDownload(timestamp, videoMetadata, None)

      _ <- transaction {
        fileResourceDao
          .insert(thumbnail)
          .productR(videoMetadataDao.insert(videoMetadata))
          .productR(schedulingDao.insert(scheduledVideoDownload))
      }
    } yield scheduledVideoDownload

  override def search(
    term: Option[String],
    videoUrls: Option[NonEmptyList[Uri]],
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order
  ): F[Seq[ScheduledVideoDownload]] =
    transaction {
      schedulingDao.search(term, videoUrls, pageNumber, pageSize, sortBy, order)
    }

  override def getById(id: String): F[ScheduledVideoDownload] =
    OptionT(transaction(schedulingDao.getById(id)))
      .getOrElseF {
        ApplicativeError[F, Throwable].raiseError(ResourceNotFoundException(s"Unable to find scheduled video download with ID = $id"))
      }

  override def updateDownloadProgress(id: String, downloadedBytes: Long): F[Unit] =
    for {
      timestamp <- JodaClock[F].timestamp

      result <- keySpacedKeyValueStore.put(DownloadProgressKey(id), DownloadProgress(id, timestamp, downloadedBytes))
    } yield result

  override def completeTask(id: String): F[ScheduledVideoDownload] =
    JodaClock[F].timestamp
      .flatMap { timestamp =>
        OptionT(transaction(schedulingDao.completeTask(id, timestamp)))
          .getOrElseF(ApplicativeError[F, Throwable].raiseError(InvalidConditionException))
      }

  override val acquireTask: OptionT[F, ScheduledVideoDownload] =
    OptionT {
      transaction(schedulingDao.retrieveTask)
    }

  override val active: Stream[F, DownloadProgress] =
    Stream
      .awakeDelay[F](Duration.create(500, TimeUnit.MILLISECONDS))
      .productR {
        Stream.eval(JodaClock[F].timestamp)
      }
      .scan[(Option[DateTime], Option[DateTime])]((None, None)) {
        case ((_, Some(previous)), timestamp) => (Some(previous), Some(timestamp))
        case (_, timestamp) => (None, Some(timestamp))
      }
      .collect {
        case (Some(start), Some(end)) => (start, end)
      }
      .evalMap {
        case (start, end) =>
          keySpacedKeyValueStore.allKeys
            .flatMap(_.traverse(keySpacedKeyValueStore.get))
            .map {
              _.collect { case Some(value) => value }
                .filter {
                  case DownloadProgress(_, updatedAt, _) => updatedAt.isAfter(start) && updatedAt.isBefore(end)
                }
            }
      }
      .flatMap { results =>
        Stream.emits(results)
      }
}
