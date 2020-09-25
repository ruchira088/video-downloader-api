package com.ruchij.services.scheduling.models

import com.ruchij.kv.keys.{KVStoreKey, KeySpace}
import org.joda.time.DateTime

case class DownloadProgress(videoId: String, updatedAt: DateTime, bytes: Long)

object DownloadProgress {
  case class DownloadProgressKey(videoId: String) extends KVStoreKey

  implicit case object DownloadProgressKeySpace extends KeySpace[DownloadProgressKey, DownloadProgress] {
    override val name: String = "download-progress"
  }
}
