package com.ruchij.core.services.video

import cats.data.Kleisli
import cats.effect.{Clock, Sync}
import cats.implicits._
import cats.{Applicative, Monad, ~>}
import com.ruchij.core.config.DownloadConfiguration
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.videometadata.VideoMetadataDao
import com.ruchij.core.daos.videometadata.models.VideoSite.Selector
import com.ruchij.core.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.core.services.download.DownloadService
import com.ruchij.core.services.hashing.HashingService
import com.ruchij.core.services.video.VideoAnalysisService.{Existing, NewlyCreated, VideoMetadataResult}
import com.ruchij.core.services.video.models.VideoAnalysisResult
import com.ruchij.core.types.JodaClock
import com.ruchij.core.utils.Http4sUtils
import org.http4s.client.Client
import org.http4s.headers.`Content-Length`
import org.http4s.{Method, Request, Uri}
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class VideoAnalysisServiceImpl[F[_]: Sync: Clock, T[_]: Monad](
  hashingService: HashingService[F],
  downloadService: DownloadService[F],
  client: Client[F],
  videoMetadataDao: VideoMetadataDao[T],
  fileResourceDao: FileResourceDao[T],
  downloadConfiguration: DownloadConfiguration
)(implicit transaction: T ~> F)
    extends VideoAnalysisService[F] {

  override def metadata(uri: Uri): F[VideoMetadataResult] =
    for {
      videoId <- hashingService.hash(uri.renderString)

      videoMetadataOpt <- transaction(videoMetadataDao.getById(videoId))

      result <- videoMetadataOpt.fold[F[VideoMetadataResult]](createMetadata(uri, videoId).map(NewlyCreated)) {
        videoMetadata => Applicative[F].pure[VideoMetadataResult](Existing(videoMetadata))
      }

    } yield result

  def createMetadata(uri: Uri, videoId: String): F[VideoMetadata] =
    for {
      VideoAnalysisResult(_, videoSite, title, duration, size, thumbnailUri) <- analyze(uri)
      timestamp <- JodaClock[F].timestamp

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

      _ <- transaction {
        fileResourceDao
          .insert(thumbnail)
          .productR(videoMetadataDao.insert(videoMetadata))
      }
    } yield videoMetadata

  override def analyze(uri: Uri): F[VideoAnalysisResult] =
    for {
      (videoSite, document) <- uriInfo(uri)
      videoAnalysisResult <- analyze(uri, videoSite).run(document)
    } yield videoAnalysisResult

  override def downloadUri(uri: Uri): F[Uri] =
    for {
      (videoSite, document) <- uriInfo(uri)
      downloadUri <- videoSite.downloadUri[F].run(document)
    } yield downloadUri

  def uriInfo(uri: Uri): F[(VideoSite, Document)] =
    for {
      videoSite <- VideoSite.infer[F](uri)

      html <- client.expect[String](uri)
      document <- Sync[F].catchNonFatal(Jsoup.parse(html))
    } yield (videoSite, document)

  def analyze(uri: Uri, videoSite: VideoSite): Selector[F, VideoAnalysisResult] =
    for {
      videoTitle <- videoSite.title[F]
      thumbnailUri <- videoSite.thumbnailUri[F]
      duration <- videoSite.duration[F]

      downloadUri <- videoSite.downloadUri[F]

      size <- Kleisli.liftF {
        client
          .run(Request[F](Method.HEAD, downloadUri))
          .use(Http4sUtils.header[F](`Content-Length`).map(_.length).run)
      }
    } yield models.VideoAnalysisResult(uri, videoSite, videoTitle, duration, size, thumbnailUri)
}
