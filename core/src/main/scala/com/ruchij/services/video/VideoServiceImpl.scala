package com.ruchij.services.video

import java.nio.file.Path
import java.util.concurrent.TimeUnit

import cats.Monad
import cats.effect.Clock
import cats.implicits._
import com.ruchij.daos.video.VideoDao
import com.ruchij.daos.video.models.Video
import com.ruchij.daos.videometadata.models.VideoMetadata
import org.joda.time.DateTime

class VideoServiceImpl[F[_]: Monad: Clock](videoDao: VideoDao[F]) extends VideoService[F] {

  override def insert(videoMetadata: VideoMetadata, path: Path): F[Video] =
    Clock[F].realTime(TimeUnit.MILLISECONDS).flatMap { timestamp =>
      val video = Video(new DateTime(timestamp), videoMetadata, path)

      videoDao.insert(video).as(video)
    }
}