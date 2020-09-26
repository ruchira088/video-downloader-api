package com.ruchij.api.web.routes

import cats.effect.Sync
import cats.implicits._
import com.ruchij.api.web.requests.{VideoAnalyzeRequest, VideoMetadataUpdateRequest}
import com.ruchij.api.web.requests.queryparams.SearchQuery
import com.ruchij.core.services.video.{VideoAnalysisService, VideoService}
import com.ruchij.api.circe.Encoders._
import com.ruchij.api.web.responses.{IterableResponse, SearchResult}
import io.circe.generic.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.circe.{decodeUri, encodeUri}
import org.http4s.dsl.Http4sDsl

object VideoRoutes {
  def apply[F[_]: Sync](videoService: VideoService[F], videoAnalysisService: VideoAnalysisService[F])(
    implicit dsl: Http4sDsl[F]
  ): HttpRoutes[F] = {
    import dsl._

    HttpRoutes.of {
      case GET -> Root / "search" :? queryParameters =>
        for {
          SearchQuery(term, videoUrls, pageSize, pageNumber, sortBy, order) <-
            SearchQuery.fromQueryParameters[F].run(queryParameters)

          videos <- videoService.search(term, videoUrls, pageNumber, pageSize, sortBy, order)

          response <- Ok(SearchResult(videos, pageNumber, pageSize, term, videoUrls, sortBy, order))
        } yield response

      case request @ POST -> Root / "analyze" =>
        for {
          videoAnalyzeRequest <- request.as[VideoAnalyzeRequest]

          result <- videoAnalysisService.metadata(videoAnalyzeRequest.url)

          response <- Ok(result)
        } yield response

      case GET -> Root / "id" / videoId => Ok(videoService.fetchById(videoId))

      case request @ PATCH -> Root / "id" / videoId / "metadata" =>
        for {
          videoMetadataUpdateRequest <- request.as[VideoMetadataUpdateRequest]

          updatedVideo <- videoService.update(videoId, videoMetadataUpdateRequest.title)

          response <- Ok(updatedVideo)
        }
        yield response

      case GET -> Root / "id" / videoId / "snapshots" =>
        videoService
          .fetchById(videoId)
          .productR(videoService.fetchVideoSnapshots(videoId))
          .flatMap(snapshots => Ok(IterableResponse(snapshots)))
    }
  }
}