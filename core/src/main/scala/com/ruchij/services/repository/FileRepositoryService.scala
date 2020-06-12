package com.ruchij.services.repository

import java.nio.file.{Files, Path, Paths, StandardOpenOption}

import cats.effect.{Blocker, ContextShift, Sync}
import cats.implicits._
import cats.{Applicative, ApplicativeError}
import com.ruchij.types.FunctionKTypes.eitherToF
import fs2.Stream
import fs2.io.file.{readRange, walk, writeAll, size => fileSize}
import org.http4s.MediaType

class FileRepositoryService[F[_]: Sync: ContextShift](ioBlocker: Blocker) extends RepositoryService[F] {

  override type BackedType = Path

  override def write(key: String, data: Stream[F, Byte]): Stream[F, Unit] =
    for {
      path <- Stream.eval(backedType(key))
      exists <- Stream.eval(fileExists(path))

      result <- data.through {
        writeAll(path, ioBlocker, if (exists) List(StandardOpenOption.APPEND) else List(StandardOpenOption.CREATE))
      }
    }
    yield result

  override def read(key: String, start: Option[Long], end: Option[Long]): F[Option[Stream[F, Byte]]] =
    backedType(key)
      .flatMap { path =>
        fileExists(path)
          .flatMap { exists =>
            if (exists)
              Sync[F].delay(
                Some(readRange(path, ioBlocker, FileRepositoryService.CHUNK_SIZE, start.getOrElse(0), end.getOrElse(Long.MaxValue)))
              )
            else
              Applicative[F].pure(None)
          }
      }

  override def size(key: String): F[Option[Long]] =
    for {
      path <- backedType(key)
      exists <- fileExists(path)

      bytes <- if (exists) fileSize(ioBlocker, path).map[Option[Long]](Some.apply) else Applicative[F].pure[Option[Long]](None)
    }
    yield bytes

  def fileExists(path: Path): F[Boolean] =
    ioBlocker.delay(path.toFile.exists())

  override def list(key: Key): Stream[F, Key] =
    Stream.eval(backedType(key))
      .flatMap(path => walk(ioBlocker, path))
      .drop(1) // drop the parent key
      .map(_.toString)

  override def mediaType(key: Key): F[Option[MediaType]] =
    for {
      path <- backedType(key)
      fileExists <- fileExists(path)

      mediaType <-
        if (fileExists)
          ioBlocker.delay(Files.probeContentType(path))
            .flatMap { contentType => eitherToF[Throwable, F].apply(MediaType.parse(contentType)) }
            .map(Some.apply)
        else
          Applicative[F].pure(None)
    }
    yield mediaType

  override def backedType(key: Key): F[Path] = FileRepositoryService.parsePath[F](key)
}

object FileRepositoryService {
  type FileRepository[F[_], A] = RepositoryService[F] { type BackedType = A }

  val CHUNK_SIZE: Int = 4096

  def parsePath[F[_]](path: String)(implicit applicativeError: ApplicativeError[F, Throwable]): F[Path] =
    applicativeError.catchNonFatal(Paths.get(path))
}
