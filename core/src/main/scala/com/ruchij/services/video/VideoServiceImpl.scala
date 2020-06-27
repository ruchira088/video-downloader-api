package com.ruchij.services.video

import cats.data.OptionT
import cats.implicits._
import cats.{ApplicativeError, MonadError, ~>}
import com.ruchij.daos.doobie.DoobieUtils.singleUpdate
import com.ruchij.daos.resource.FileResourceDao
import com.ruchij.daos.resource.models.FileResource
import com.ruchij.daos.snapshot.SnapshotDao
import com.ruchij.daos.snapshot.models.Snapshot
import com.ruchij.daos.video.VideoDao
import com.ruchij.daos.video.models.Video
import com.ruchij.daos.videometadata.VideoMetadataDao
import com.ruchij.exceptions.ResourceNotFoundException
import com.ruchij.services.models.{Order, SortBy}

class VideoServiceImpl[F[_]: MonadError[*[_], Throwable], T[_]: MonadError[*[_], Throwable]](
  videoDao: VideoDao[T],
  videoMetadataDao: VideoMetadataDao[T],
  snapshotDao: SnapshotDao[T],
  fileResourceDao: FileResourceDao[T]
)(implicit transaction: T ~> F)
    extends VideoService[F] {

  override def insert(videoMetadataKey: String, fileResource: FileResource): F[Video] =
    transaction {
      fileResourceDao
        .insert(fileResource)
        .productR(videoDao.insert(videoMetadataKey, fileResource.id))
    }.productR(fetchById(videoMetadataKey))

  override def fetchById(videoId: String): F[Video] =
    OptionT(transaction(videoDao.findById(videoId)))
      .getOrElseF {
        MonadError[F, Throwable].raiseError(ResourceNotFoundException(s"Unable to find video with ID: $videoId"))
      }

  override def search(term: Option[String], pageNumber: Int, pageSize: Int, sortBy: SortBy, order: Order): F[Seq[Video]] =
    transaction {
      videoDao.search(term, pageNumber, pageSize, sortBy, order)
    }

  override def fetchVideoSnapshots(videoId: String): F[Seq[Snapshot]] =
    transaction {
      snapshotDao.findByVideo(videoId)
    }

  override def update(videoId: String, title: Option[String]): F[Video] =
    transaction(videoMetadataDao.update(videoId, title))
      .productR(fetchById(videoId))

  override def deleteById(videoId: String): F[Video] =
    fetchById(videoId)
        .flatMap { video =>
          transaction {
            OptionT.liftF(snapshotDao.deleteByVideo(video.videoMetadata.id))
              .productR(singleUpdate(videoDao.deleteById(videoId)))
              .productR(singleUpdate(videoMetadataDao.deleteById(video.videoMetadata.id)))
              .productR(singleUpdate(fileResourceDao.deleteById(video.videoMetadata.thumbnail.id)))
              .productR(singleUpdate(fileResourceDao.deleteById(video.fileResource.id)))
              .getOrElseF {
                ApplicativeError[T, Throwable].raiseError {
                  new InternalError(s"Unable to delete video with ID = $videoId")
                }
              }
              .as(video)
          }
        }

}
