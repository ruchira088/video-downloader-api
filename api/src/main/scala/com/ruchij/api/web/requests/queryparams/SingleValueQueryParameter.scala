package com.ruchij.api.web.requests.queryparams

import cats.ApplicativeError
import cats.data.Kleisli
import QueryParameter.{enumQueryParamDecoder, optionQueryParamDecoder}
import QueryParameter.QueryParameters
import com.ruchij.core.services.models.{Order, SortBy}
import org.http4s.QueryParamDecoder

abstract class SingleValueQueryParameter[A: QueryParamDecoder](key: String, defaultValue: A)
    extends QueryParameter[A] {
  override def parse[F[_]: ApplicativeError[*[_], Throwable]]: Kleisli[F, QueryParameters, A] =
    QueryParameter.parse[F, A](key).map(_.headOption.getOrElse(defaultValue))
}

object SingleValueQueryParameter {
  case object PageNumberQueryParameter extends SingleValueQueryParameter(key = "page-number", defaultValue = 0)

  case object PageSizeQueryParameter extends SingleValueQueryParameter(key = "page-size", defaultValue = 10)

  case object SearchTermQueryParameter extends SingleValueQueryParameter[Option[String]](key = "search-term", defaultValue = None)

  case object SortByQueryParameter extends SingleValueQueryParameter[SortBy]("sort-by", defaultValue = SortBy.Date)

  case object OrderQueryParameter extends SingleValueQueryParameter[Order]("order", defaultValue = Order.Descending)
}