package com.ruchij.services.download

import cats.effect.Resource
import com.ruchij.services.download.models.DownloadResult
import org.http4s.Uri

trait DownloadService[F[_]] {
  def download(url: Uri, parent: String): Resource[F, DownloadResult[F]]
}
