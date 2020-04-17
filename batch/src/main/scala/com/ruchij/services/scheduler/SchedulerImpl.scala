package com.ruchij.services.scheduler

import java.util.concurrent.TimeUnit

import cats.{Applicative, Monad}
import cats.effect.concurrent.Semaphore
import cats.effect.{Clock, Concurrent, Resource, Sync, Timer}
import cats.implicits._
import com.ruchij.config.WorkerConfiguration
import com.ruchij.services.scheduler.SchedulerImpl.MAX_DELAY
import com.ruchij.services.scheduling.SchedulingService
import com.ruchij.services.worker.WorkExecutor
import org.joda.time.{DateTime, LocalTime}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

class SchedulerImpl[F[_]: Concurrent: Timer](
  schedulingService: SchedulingService[F],
  workExecutor: WorkExecutor[F],
  workerConfiguration: WorkerConfiguration
) extends Scheduler[F] {

  override type Result = Nothing

  override val run: F[Nothing] =
    Semaphore[F](workerConfiguration.maxConcurrentDownloads).flatMap[Nothing](runScheduler)

  def runScheduler(semaphore: Semaphore[F]): F[Nothing] =
    semaphore.acquire
      .product {
        Sync[F]
          .delay(Random.nextLong(MAX_DELAY.toMillis))
          .flatMap { long =>
            Timer[F].sleep(FiniteDuration(long, TimeUnit.MILLISECONDS))
          }
      }
      .productR(SchedulerImpl.isWorkPeriod(workerConfiguration.startTime, workerConfiguration.endTime))
      .flatMap { isWorkPeriod =>
        if (isWorkPeriod)
          Concurrent[F]
            .start {
              Resource
                .make(schedulingService.acquireTask.value)(_ => semaphore.release)
                .use {
                  _.fold(Applicative[F].unit) { task =>
                    workExecutor.execute(task).productR(Applicative[F].unit)
                  }
                }
            }
            .productR(Applicative[F].unit)
        else
          Applicative[F].unit
      }
      .productR[Nothing] {
        Sync[F].defer[Nothing](runScheduler(semaphore))
      }
}

object SchedulerImpl {
  val MAX_DELAY: FiniteDuration = 20 seconds

  def isWorkPeriod[F[_]: Clock: Monad](start: LocalTime, end: LocalTime): F[Boolean] =
    Clock[F]
      .realTime(TimeUnit.MILLISECONDS)
      .map { timestamp =>
        val localTime = new DateTime(timestamp).toLocalTime

        if (start.isBefore(end))
          localTime.isAfter(start) && localTime.isBefore(end)
        else
          localTime.isAfter(start) || localTime.isBefore(end)
      }
}
