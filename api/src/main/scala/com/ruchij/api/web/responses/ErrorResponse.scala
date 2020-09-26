package com.ruchij.api.web.responses

import cats.Applicative
import com.ruchij.api.circe.Encoders.throwableEncoder
import io.circe.generic.auto._
import org.http4s.EntityEncoder
import org.http4s.circe.jsonEncoderOf

case class ErrorResponse(errorMessages: Seq[Throwable])

object ErrorResponse {
  implicit def errorResponseEncoder[F[_]: Applicative]: EntityEncoder[F, ErrorResponse] =
    jsonEncoderOf[F, ErrorResponse]
}