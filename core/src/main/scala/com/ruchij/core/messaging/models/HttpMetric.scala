package com.ruchij.core.messaging.models

import org.http4s.{Method, Status, Uri}

import scala.concurrent.duration.FiniteDuration

case class HttpMetric(method: Method, uri: Uri, finiteDuration: FiniteDuration, responseStatus: Status)