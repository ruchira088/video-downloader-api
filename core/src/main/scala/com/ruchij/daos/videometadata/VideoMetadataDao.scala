package com.ruchij.daos.videometadata

import com.ruchij.daos.videometadata.models.VideoMetadata

trait VideoMetadataDao[F[_]] {
  def insert(videoMetadata: VideoMetadata): F[Int]

  def update(videoMetadataId: String, title: Option[String]): F[Int]

  def getById(videoMetadataId: String): F[Option[VideoMetadata]]

  def deleteById(videoMetadataId: String): F[Int]
}
