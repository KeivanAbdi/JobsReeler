package com.keivanabdi.jobsreeler.utils

import java.io.{BufferedWriter, File, FileWriter}
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.Using

import com.keivanabdi.jobsreeler.models.config.CacheConfig
import org.slf4j.LoggerFactory

class Cache(using cacheConfig: CacheConfig) {

  val logger = LoggerFactory.getLogger(getClass.getName)

  def cacheInitialization(): Unit =
    val cacheFolder = new File(cacheConfig.path)
    if (!new File(cacheConfig.path).exists) {
      logger.info(s"creating cache directory in ${cacheConfig.path}")
      cacheFolder.mkdir()
    }

  cacheInitialization()

  private def cacheFilePath(
      key: String
  ): String =
    s"${cacheConfig.path}/$key"

  def getOrElseUpdate(
      key: String
  )(
      f: => Future[String]
  )(using ExecutionContext): Future[String] =
    val cacheFile = new File(cacheFilePath(key))
    if (cacheFile.exists) {
      logger.info(s"getting content of $key from cache")
      Using.resource(Source.fromFile(cacheFile)) { bufferedSource =>
        val content = bufferedSource.mkString
        Future.successful(content)
      }
    } else {
      logger.warn(s"content of $key not found in cache")
      f.map { result =>
        Using.resource(new BufferedWriter(new FileWriter(cacheFile))) { bw =>
          bw.write(result)
          result
        }
      }
    }

}
