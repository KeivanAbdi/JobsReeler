package com.keivanabdi.jobsreeler.streams

import LinkedInJobFlows.*
import sttp.capabilities.pekko.PekkoStreams
import sttp.client4.WebSocketStreamBackend

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.Source

import scala.collection.immutable.SortedSet
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.chaining._

import com.keivanabdi.datareeler.models.*
import com.keivanabdi.datareeler.templates.FullWidthInfiniteScroll
import com.keivanabdi.datareeler.templates.FullWidthInfiniteScroll.Instructions
import com.keivanabdi.jobsreeler.ai.GermanLevelDetection.GermanLanguageLevel
import com.keivanabdi.jobsreeler.models.config.AIConfig
import com.keivanabdi.jobsreeler.models.config.AppConfig
import com.keivanabdi.jobsreeler.models.job.JobDetail
import com.keivanabdi.jobsreeler.models.job.JobMetaData
import com.keivanabdi.jobsreeler.models.job.JobSummary
import com.keivanabdi.jobsreeler.models.job.JobType
import com.keivanabdi.jobsreeler.models.job.Log
import com.keivanabdi.jobsreeler.scrapers.LinkedInJobsScrapper
import com.keivanabdi.jobsreeler.utils.Cache
import com.keivanabdi.jobsreeler.utils.VisaSponsorshipHelper.*
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

object GermanScalaJobsStreamProfile extends StreamProfile {
  private val logger = LoggerFactory.getLogger(getClass.getName)

  override val initialUrl: String =
    "https://www.linkedin.com/jobs/search/?keywords=scala&geoId=101282230&sortBy=DD&origin=JOB_SEARCH_PAGE_SEARCH_BUTTON"

  private val blackListWords: Set[String] =
    Set("golang", "rust", "nextjs", "android")
  private val whitelistWords: Set[String] = Set("scala")

  def buildJobStream(
      linkedInScrapper: LinkedInJobsScrapper
  )(using
      ActorSystem,
      ExecutionContext,
      Ordering[Log],
      AppConfig,
      WebSocketStreamBackend[Future, PekkoStreams],
      Cache
  ): Source[ReelElement[JobDetail, JobMetaData, Instructions], ?] = {
    lazy val initialMappingFlow: Flow[
      JobSummary,
      ReelElement[JobSummary, JobMetaData, Instructions],
      NotUsed
    ] =
      Flow[JobSummary]
        .statefulMapConcat { () =>
          var index: Long = 0L
          jobSummary =>
            index += 1
            Iterable.single(
              ReelElement(
                userData = Some(jobSummary),
                userMetaData = Some(
                  JobMetaData(
                    incomingItemsCount = Some(index),
                    lastUpdateTime     = DateTime.now(),
                    logs               = SortedSet.empty
                  )
                )
              )
            )
        }

    lazy val finalTransformFlow: Flow[
      ReelElement[JobDetail, JobMetaData, Instructions],
      ReelElement[JobDetail, JobMetaData, Instructions],
      NotUsed
    ] =
      Flow[ReelElement[JobDetail, JobMetaData, Instructions]]
        .statefulMapConcat { () =>
          var transformedStreamItemsCount: Long = 0L
          var lastRawStreamItemsCount    : Long = 0L

          var previouslogs: SortedSet[Log] = SortedSet.empty
          { element =>
            element.userMetaData.flatMap(_.incomingItemsCount).foreach { x =>
              lastRawStreamItemsCount = x
            }

            element.userMetaData.map(_.logs).foreach { logs =>
              previouslogs ++= logs
            }
            if (element.userData.isDefined) transformedStreamItemsCount += 1
            Iterable.single(
              element
                .copy(
                  userMetaData = element.userMetaData
                    .map(
                      _.copy(
                        outputItemsCount   = Some(transformedStreamItemsCount),
                        incomingItemsCount = Some(lastRawStreamItemsCount),
                        logs               = previouslogs
                      )
                    )
                )
            )
          }
        }
    Source.fromFutureSource {
      findVisaSponsoringCompanyUsernames("germany").map:
        case Left(error) =>
          Source.failed(
            new RuntimeException(
              s"Couldn't load the visa sponsorship company list: $error"
            )
          )
        case Right(visaSponsoringCompanyUsernames) =>
          given AIConfig = summon[AppConfig].ai
          val (logActor, logStream) =
            Source
              .actorRef[ReelElement[JobDetail, JobMetaData, Instructions]](
                completionMatcher = PartialFunction.empty,
                failureMatcher    = PartialFunction.empty,
                bufferSize        = 25,
                overflowStrategy  = OverflowStrategy.dropHead
              )
              .preMaterialize()
          val originalStream =
            Source
              .single {
                ReelElement(
                  userData = None,
                  userMetaData = Some(
                    JobMetaData(
                      incomingItemsCount = Some(0),
                      lastUpdateTime     = DateTime.now(),
                      logs = SortedSet(
                        Log(
                          time    = DateTime.now(),
                          content = s"Started"
                        )
                      )
                    )
                  ),
                  templateInstructions = None
                )
              }
              .concat(
                linkedInScrapper
                  .jobsSummarySource(logActor)
                  .via(
                    initialMappingFlow
                  )
              )
              .via(
                filterJobType(
                  JobType.Hybrid,
                  JobType.OnSite
                )
              )
              .via(
                filterBlacklistFlow(
                  blackLists       = blackListWords,
                  targetField      = _.title.pipe(Some(_)),
                  companyNameField = _.company,
                  jobLink          = _.link
                )
              )
              .via(
                filterVisaSponsorshipCompanies(
                  companyUsernames = visaSponsoringCompanyUsernames,
                  targetField      = _.companyUsername,
                  companyNameField = _.company,
                  jobLink          = _.link
                )
              )
              .via(
                filterLanguages(
                  targetField = _.title.pipe(Some(_)),
                  allowedLanguages = Seq(
                    "german",
                    "english"
                  ),
                  companyNameField = _.company,
                  jobLink          = _.link
                )
              )
              .via(
                jobDetailFlow(
                  linkedInScrapper
                )
              )
              .via(
                filterWhitelistFlow(
                  whitelists       = whitelistWords,
                  targetField      = _.descriptionText.pipe(Some(_)),
                  companyNameField = _.summary.company,
                  jobLink          = _.summary.link
                )
              )
              .via(
                filterGermanLanguageLevel(
                  targetField      = _.descriptionText.pipe(Some(_)),
                  maxGermanLevel   = GermanLanguageLevel.A2,
                  companyNameField = _.summary.company,
                  jobLink          = _.summary.link
                )
              )

          originalStream
            .merge(logStream)
            .recoverWith[ReelElement[JobDetail, JobMetaData, Instructions]] {
              case e =>
                logger.error(
                  s"Something went wrong during stream generation ${e.getMessage}",
                  e
                )
                Source.single(
                  ReelElement[
                    JobDetail,
                    JobMetaData,
                    FullWidthInfiniteScroll.Instructions
                  ](
                    userData = None,
                    userMetaData = Some(
                      JobMetaData(
                        incomingItemsCount = None,
                        lastUpdateTime     = DateTime.now(),
                        logs               = SortedSet(Log(DateTime.now(), e.toString()))
                      )
                    )
                  )
                )
            }
            .via(finalTransformFlow)
    }

  }

}
