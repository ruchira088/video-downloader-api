package com.ruchij.core.utils

import cats.data.{Kleisli, NonEmptyList}
import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadError}
import com.ruchij.core.daos.videometadata.models.VideoSite.Selector
import com.ruchij.core.exceptions.JSoupException._
import com.ruchij.core.types.FunctionKTypes.{FunctionK2TypeOps, eitherToF}
import org.http4s.Uri
import org.jsoup.nodes.{Document, Element}

import scala.jdk.CollectionConverters._

object JsoupSelector {

  def singleElement[F[_]: MonadError[*[_], Throwable]](css: String): Selector[F, Element] =
    nonEmptyElementList[F](css).flatMap {
      case NonEmptyList(head, Nil) => Kleisli.pure(head)
      case elements =>
        Kleisli.ask[F, Document].flatMapF { document =>
          ApplicativeError[F, Throwable].raiseError(MultipleElementsFoundException(document, css, elements))
        }
    }

  def nonEmptyElementList[F[_]: MonadError[*[_], Throwable]](css: String): Selector[F, NonEmptyList[Element]] =
    select[F](css).flatMap {
      case Nil =>
        Kleisli { document =>
          ApplicativeError[F, Throwable].raiseError(NoMatchingElementsFoundException(document, css))
        }

      case head :: tail => Kleisli(_ => Applicative[F].pure(NonEmptyList(head, tail)))
    }

  def select[F[_]: ApplicativeError[*[_], Throwable]](css: String): Selector[F, List[Element]] =
    Kleisli { document =>
      ApplicativeError[F, Throwable]
        .catchNonFatal(document.select(css))
        .map(_.asScala.toList)
    }

  def text[F[_]: ApplicativeError[*[_], Throwable]](element: Element): F[String] =
    parseProperty[Throwable, F](element.text(), TextNotFoundInElementException(element))

  def src[F[_]: MonadError[*[_], Throwable]](element: Element): F[Uri] =
    attribute[F](element, "src")
      .flatMap { uri =>
        Uri.fromString(uri).toType[F, Throwable]
      }

  def attribute[F[_]: ApplicativeError[*[_], Throwable]](element: Element, attributeKey: String): F[String] =
    parseProperty[Throwable, F](element.attr(attributeKey), AttributeNotFoundInElementException(element, attributeKey))

  private def parseProperty[E, F[_]: ApplicativeError[*[_], E]](value: String, onEmpty: => E): F[String] =
    Option(value)
      .map(_.trim)
      .filter(_.nonEmpty)
      .fold[F[String]](ApplicativeError[F, E].raiseError(onEmpty)) { string =>
        Applicative[F].pure(string)
      }

}
