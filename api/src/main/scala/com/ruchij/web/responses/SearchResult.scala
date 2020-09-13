package com.ruchij.web.responses

import cats.data.NonEmptyList
import com.ruchij.services.models.{Order, SortBy}
import org.http4s.Uri

case class SearchResult[A](
  results: Seq[A],
  pageNumber: Int,
  pageSize: Int,
  searchTerm: Option[String],
  videoUrls: Option[NonEmptyList[Uri]],
  sortBy: SortBy,
  order: Order
)
