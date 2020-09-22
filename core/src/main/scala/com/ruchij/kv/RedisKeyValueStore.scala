package com.ruchij.kv

import cats.{Applicative, Monad}
import cats.implicits._
import com.ruchij.kv.codecs.{KVDecoder, KVEncoder}
import dev.profunktor.redis4cats.RedisCommands

class RedisKeyValueStore[F[_]: Monad](redisCommands: RedisCommands[F, String, String]) extends KeyValueStore[F] {
  override type InsertionResult = Unit
  override type DeletionResult = Unit

  override def get[K: KVEncoder[F, *], V: KVDecoder[F, *]](key: K): F[Option[V]] =
    for {
      encodedKey <- KVEncoder[F, K].encode(key)
      fetchedValue <- redisCommands.get(encodedKey)
      value <- fetchedValue.fold[F[Option[V]]](Applicative[F].pure(None)) { stringValue =>
        KVDecoder[F, V].decode(stringValue).map(Some.apply)
      }
    } yield value

  override def put[K: KVEncoder[F, *], V: KVEncoder[F, *]](key: K, value: V): F[InsertionResult] =
    for {
      encodedKey <- KVEncoder[F, K].encode(key)
      encodedValue <- KVEncoder[F, V].encode(value)

      result <- redisCommands.set(encodedKey, encodedValue)
    } yield result

  override def remove[K: KVEncoder[F, *]](key: K): F[Unit] =
    for {
      encodedKey <- KVEncoder[F, K].encode(key)
      result <- redisCommands.del(encodedKey)
    } yield result

  override def keys[K: KVDecoder[F, *]](term: String): F[List[K]] =
    for {
      encodedKeys <- redisCommands.keys("*" + term + "*")
      keys <- encodedKeys.traverse(KVDecoder[F, K].decode)
    } yield keys
}