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
import com.keivanabdi.jobsreeler.models.config.AIConfig
import com.keivanabdi.jobsreeler.models.config.AppConfig
import com.keivanabdi.jobsreeler.models.config.DACHScalaJobsConfig
import com.keivanabdi.jobsreeler.models.job.JobDetail
import com.keivanabdi.jobsreeler.models.job.JobMetaData
import com.keivanabdi.jobsreeler.models.job.JobSummary
import com.keivanabdi.jobsreeler.models.job.Log
import com.keivanabdi.jobsreeler.scrapers.LinkedInJobsScrapper
import com.keivanabdi.jobsreeler.utils.Cache
import com.keivanabdi.jobsreeler.utils.VisaSponsorshipHelper.*
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

trait DACHScalaJobsStreamProfile extends StreamProfile {
  private val logger = LoggerFactory.getLogger(getClass.getName)

  val geoId      : String
  val countryName: String

  lazy val initialUrl: String =
    s"https://www.linkedin.com/jobs/search/?keywords=scala&geoId=$geoId&sortBy=DD&f_WT=1%2C3&origin=JOB_SEARCH_PAGE_SEARCH_BUTTON"

  def buildJobStream(
      linkedInScrapper: LinkedInJobsScrapper
  )(using
      actorSystem: ActorSystem,
      ec         : ExecutionContext,
      ordering   : Ordering[Log],
      appConfig  : AppConfig,
      wsBackend  : WebSocketStreamBackend[Future, PekkoStreams],
      cache      : Cache
  ): Source[ReelElement[JobDetail, JobMetaData, Instructions], ?] = {
    logger.debug(
      s"Building a job String with this profile configs: ${appConfig.sourceProfile}"
    )
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
      findVisaSponsoringCompanyUsernames(countryName).map:
        case Left(error) =>
          Source.failed(
            new RuntimeException(
              s"Couldn't load the visa sponsorship company list: $error"
            )
          )
        case Right(visaSponsoringCompanyUsernames) =>
          given AIConfig = appConfig.ai

          val dachSourceProfileConfig: DACHScalaJobsConfig =
            (appConfig.sourceProfile: @unchecked) match {
              case dachScalaJobsConfig: DACHScalaJobsConfig =>
                dachScalaJobsConfig
              case other =>
                val errorMsg =
                  s"Incompatible source profile configuration: ${other.getClass.getName} for DACHScalaJobsStreamProfile. Expected a DACHScalaJobsConfig."
                logger.error(errorMsg)
                throw new IllegalArgumentException(errorMsg)
            }

          val (logActor, logStream) =
            Source
              .actorRef[ReelElement[JobDetail, JobMetaData, Instructions]](
                completionMatcher = PartialFunction.empty,
                failureMatcher    = PartialFunction.empty,
                bufferSize        = 25,
                overflowStrategy  = OverflowStrategy.dropHead
              )
              .preMaterialize()

          val originalStream: Source[
            ReelElement[JobDetail, JobMetaData, Instructions],
            NotUsed
          ] =
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
                  dachSourceProfileConfig.jobTypes
                )
              )
              .via(
                filterBlacklistFlow(
                  blacklistWords   = dachSourceProfileConfig.blacklistWords,
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
                  whitelistWords   = dachSourceProfileConfig.whitelistWords,
                  targetField      = _.descriptionText.pipe(Some(_)),
                  companyNameField = _.summary.company,
                  jobLink          = _.summary.link
                )
              )
              .via(
                filterGermanLanguageLevel(
                  targetField      = _.descriptionText.pipe(Some(_)),
                  maxGermanLevel   = dachSourceProfileConfig.germanLanguageLevel,
                  companyNameField = _.summary.company,
                  jobLink          = _.summary.link
                )
              )

          // Merging and transforming logStream and originalStream into one final stream
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
