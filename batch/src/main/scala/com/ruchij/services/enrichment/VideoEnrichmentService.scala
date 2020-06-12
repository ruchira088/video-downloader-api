package com.ruchij.services.enrichment

import com.ruchij.daos.resource.models.FileResource
import com.ruchij.daos.snapshot.models.Snapshot
import com.ruchij.daos.video.models.Video
import org.http4s.MediaType

import scala.concurrent.duration.FiniteDuration

trait VideoEnrichmentService[F[_]] {
  val snapshotMediaType: MediaType

  def videoSnapshots(video: Video): F[Seq[Snapshot]]

  def snapshotFileResource(videoPath: String, snapshotPath: String, videoTimestamp: FiniteDuration): F[FileResource]
}

object VideoEnrichmentService {

  def snapshotTimestamps(video: Video, snapshotCount: Int): Seq[FiniteDuration] = {
    val period = video.videoMetadata.duration / (snapshotCount + 1)

    Range(1, snapshotCount).map(_ * period)
  }
}
