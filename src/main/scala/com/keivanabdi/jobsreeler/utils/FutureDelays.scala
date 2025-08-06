package com.keivanabdi.jobsreeler.utils

import org.apache.pekko.actor.ActorSystem

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._

object FutureDelays {

  def delay(
      milliseconds: Long
  )(using system: ActorSystem, ec: ExecutionContext): Future[Unit] =
    val promise = Promise[Unit]()
    system.scheduler.scheduleOnce(milliseconds.milliseconds) {
      promise.success(())
    }
    promise.future

}
