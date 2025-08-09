package com.keivanabdi.jobsreeler.streams

import sttp.capabilities.pekko.PekkoStreams
import sttp.client4.WebSocketStreamBackend

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import com.keivanabdi.datareeler.models.ReelElement
import com.keivanabdi.datareeler.templates.FullWidthInfiniteScroll.Instructions
import com.keivanabdi.jobsreeler.models.config.AppConfig
import com.keivanabdi.jobsreeler.models.job.JobDetail
import com.keivanabdi.jobsreeler.models.job.JobMetaData
import com.keivanabdi.jobsreeler.models.job.Log
import com.keivanabdi.jobsreeler.scrapers.LinkedInJobsScrapper
import com.keivanabdi.jobsreeler.utils.Cache

trait StreamProfile {
  def initialUrl: String

  def buildJobStream(
      linkedInScrapper: LinkedInJobsScrapper
  )(using
      ActorSystem,
      ExecutionContext,
      Ordering[Log],
      AppConfig,
      WebSocketStreamBackend[Future, PekkoStreams],
      Cache
  ): Source[ReelElement[JobDetail, JobMetaData, Instructions], ?]

}
