package com.ruchij.daos.scheduling

import cats.ApplicativeError
import cats.data.OptionT
import cats.effect.Bracket
import cats.implicits._
import doobie.implicits._
import com.ruchij.daos.doobie.DoobieCustomMappings._
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.exceptions.InvalidConditionException
import doobie.util.fragment.Fragment
import doobie.util.transactor.Transactor
import org.http4s.Uri
import org.joda.time.DateTime

class DoobieSchedulingDao[F[_]: Bracket[*[_], Throwable]](
  doobieVideoMetadataDao: DoobieVideoMetadataDao[F],
  transactor: Transactor.Aux[F, Unit]
) extends SchedulingDao[F] {

  override def insert(scheduledVideoDownload: ScheduledVideoDownload): F[Int] =
    doobieVideoMetadataDao
      .insert(scheduledVideoDownload.videoMetadata)
      .productR {
        sql"""
          INSERT INTO scheduled_video (scheduled_at, last_updated_at, in_progress, url, downloaded_bytes, completed_at)
            VALUES (
              ${scheduledVideoDownload.scheduledAt},
              ${scheduledVideoDownload.lastUpdatedAt},
              ${scheduledVideoDownload.inProgress},
              ${scheduledVideoDownload.videoMetadata.url},
              ${scheduledVideoDownload.downloadedBytes},
              ${scheduledVideoDownload.completedAt}
              )
         """
          .update.run.transact(transactor)
      }

  override def updateDownloadProgress(url: Uri, downloadedBytes: Long, timestamp: DateTime): F[Int] =
    sql"""
      UPDATE scheduled_video SET downloaded_bytes = $downloadedBytes, last_updated_at = $timestamp
        where url = $url
    """
      .update.run.transact(transactor)

  val SELECT_QUERY: Fragment =
    sql"""
        SELECT
          scheduled_video.scheduled_at, scheduled_video.last_updated_at, scheduled_video.in_progress,
          video_metadata.url, video_metadata.video_site, video_metadata.title, video_metadata.duration,
          video_metadata.size, video_metadata.thumbnail,
          scheduled_video.downloaded_bytes, scheduled_video.completed_at
        FROM scheduled_video
        JOIN video_metadata ON scheduled_video.url = video_metadata.url
      """

  override def getByUrl(url: Uri): OptionT[F, ScheduledVideoDownload] =
    OptionT {
      (SELECT_QUERY ++ sql"WHERE scheduled_video.url = $url")
        .query[ScheduledVideoDownload].option.transact(transactor)
    }

  override def setInProgress(url: Uri, inProgress: Boolean): OptionT[F, ScheduledVideoDownload] =
    OptionT.liftF {
      sql"UPDATE scheduled_video SET in_progress = $inProgress WHERE url = $url AND in_progress = ${!inProgress}"
        .update.run.transact(transactor)
    }
      .flatMap {
        case 0 => OptionT.none
        case 1 => getByUrl(url)
        case _ => OptionT.liftF(ApplicativeError[F, Throwable].raiseError(InvalidConditionException))
      }

  override val retrieveNewTask: OptionT[F, ScheduledVideoDownload] =
    OptionT {
      (SELECT_QUERY ++ sql"WHERE scheduled_video.in_progress = false")
        .query[ScheduledVideoDownload].option.transact(transactor)
    }
}