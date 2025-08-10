package com.keivanabdi.jobsreeler.streams

import sttp.capabilities.pekko.PekkoStreams
import sttp.client4.WebSocketStreamBackend

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Flow

import scala.collection.immutable.SortedSet
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.matching.Regex

import com.keivanabdi.datareeler.models.ReelElement
import com.keivanabdi.datareeler.templates.FullWidthInfiniteScroll.Instructions
import com.keivanabdi.jobsreeler.ai.GermanLevelDetection
import com.keivanabdi.jobsreeler.ai.GermanLevelDetection.GermanLanguageLevel
import com.keivanabdi.jobsreeler.ai.GermanLevelDetection.GermanLevelResponse
import com.keivanabdi.jobsreeler.ai.LanguageDetection
import com.keivanabdi.jobsreeler.models.*
import com.keivanabdi.jobsreeler.models.config.AIConfig
import com.keivanabdi.jobsreeler.models.config.AppConfig
import com.keivanabdi.jobsreeler.models.job.*
import com.keivanabdi.jobsreeler.models.job.JobMetaData
import com.keivanabdi.jobsreeler.models.job.JobType
import com.keivanabdi.jobsreeler.models.job.Log
import com.keivanabdi.jobsreeler.scrapers.LinkedInJobsScrapper
import com.keivanabdi.jobsreeler.utils.HtmlHelper.*
import org.joda.time.DateTime

object LinkedInJobFlows {

  def jobDetailFlow(
      linkedInScrapper: LinkedInJobsScrapper
  )(using
      ExecutionContext,
      ActorSystem
  ): Flow[
    ReelElement[JobSummary, JobMetaData, Instructions],
    ReelElement[JobDetail, JobMetaData, Instructions],
    NotUsed
  ] =
    Flow[ReelElement[JobSummary, JobMetaData, Instructions]]
      .mapAsync(1) { elementWithSummary =>
        elementWithSummary.userData
          .map(linkedInScrapper.jobDetail.andThen(_.map(Option[JobDetail](_))))
          .getOrElse(Future.successful(Option.empty[JobDetail]))
          .map { newUserData =>
            elementWithSummary.copy(userData = newUserData)
          }
      }

  def filterJobType(
      allowedJobTypes: JobType*
  )(using Ordering[Log]): Flow[
    ReelElement[JobSummary, JobMetaData, Instructions],
    ReelElement[JobSummary, JobMetaData, Instructions],
    NotUsed
  ] =
    Flow[ReelElement[JobSummary, JobMetaData, Instructions]]
      .map {
        case reelElement @ ReelElement(
              Some(userData),
              userMetaData,
              templateInstructions
            ) if userData.jobType.forall(allowedJobTypes.contains) =>
          reelElement
        case ReelElement(Some(userData), userMetaData, templateInstructions) =>
          ReelElement(
            userData = None,
            userMetaData = Some(
              JobMetaData(
                incomingItemsCount = userMetaData.flatMap(_.incomingItemsCount),
                lastUpdateTime     = DateTime.now(),
                logs = SortedSet(
                  Log(
                    time = DateTime.now(),
                    content =
                      s"Filtered a '${userData.jobType.mkString}' ${link("job", userData.link)} from '${userData.company.mkString}'"
                  )
                )
              )
            ),
            templateInstructions = None
          )

        case reelElement => reelElement

      }

  def filterVisaSponsorshipCompanies[A](
      companyUsernames: Set[String],
      targetField     : A => Option[String],
      companyNameField: A => String,
      jobLink         : A => String
  )(using Ordering[Log]) =
    Flow[ReelElement[A, JobMetaData, Instructions]]
      .map {
        case reelElement @ ReelElement(
              Some(userData),
              userMetaData,
              templateInstructions
            ) =>
          targetField(userData) match {
            case Some(fieldValue) =>
              companyUsernames.find(username =>
                fieldValue.contains(username)
              ) match {
                case Some(_) =>
                  reelElement

                case None =>
                  ReelElement[A, JobMetaData, Instructions](
                    userData = None,
                    userMetaData = Some(
                      JobMetaData(
                        incomingItemsCount =
                          userMetaData.flatMap(_.incomingItemsCount),
                        lastUpdateTime = DateTime.now(),
                        logs = SortedSet(
                          Log(
                            time = DateTime.now(),
                            content =
                              s"Filtered a ${link("job", jobLink(userData))} from '${companyNameField(userData)}' for not being included in the visa-sponsorship company list"
                          )
                        )
                      )
                    ),
                    templateInstructions = None
                  )
              }

            case None => reelElement
          }
        case reelElement => reelElement

      }

  def filterBlacklistFlow[A](
      blacklistWords  : Set[String],
      targetField     : A => Option[String],
      companyNameField: A => String,
      jobLink         : A => String
  )(using Ordering[Log]): Flow[
    ReelElement[A, JobMetaData, Instructions],
    ReelElement[A, JobMetaData, Instructions],
    NotUsed
  ] =
    Flow[ReelElement[A, JobMetaData, Instructions]]
      .map {
        case reelElement @ ReelElement(Some(userData), userMetaData, _) =>
          targetField(userData) match {
            case Some(fieldValue) =>
              blacklistWords.find(blk =>
                fieldValue.toLowerCase.contains(blk)
              ) match {
                case None => reelElement

                case Some(blacklistedWord) =>
                  ReelElement[A, JobMetaData, Instructions](
                    userData = None,
                    userMetaData = Some(
                      JobMetaData(
                        incomingItemsCount =
                          userMetaData.flatMap(_.incomingItemsCount),
                        lastUpdateTime = DateTime.now(),
                        logs = SortedSet(
                          Log(
                            time = DateTime.now(),
                            content =
                              s"Filtered a ${link("job", jobLink(userData))} from '${companyNameField(userData)}' for containing a blacklisted word '$blacklistedWord'"
                          )
                        )
                      )
                    ),
                    templateInstructions = None
                  )
              }

            case None => reelElement
          }

        case reelElement => reelElement
      }

  def filterWhitelistFlow[A](
      whitelistWords  : Set[String],
      targetField     : A => Option[String],
      companyNameField: A => String,
      jobLink         : A => String
  )(using Ordering[Log]): Flow[
    ReelElement[A, JobMetaData, Instructions],
    ReelElement[A, JobMetaData, Instructions],
    NotUsed
  ] =
    Flow[ReelElement[A, JobMetaData, Instructions]]
      .map {
        case reelElement @ ReelElement(Some(userData), userMetaData, _) =>
          targetField(userData) match {
            case Some(fieldValue) =>
              val fieldValueLower = fieldValue.toLowerCase

              whitelistWords.find { whitelistedWord =>
                val pattern = s"\\b${Regex.quote(whitelistedWord)}\\b".r
                pattern.findFirstIn(fieldValueLower).isDefined
              } match {
                case Some(_) =>
                  reelElement

                case None =>
                  ReelElement[A, JobMetaData, Instructions](
                    userData = None,
                    userMetaData = Some(
                      JobMetaData(
                        incomingItemsCount =
                          userMetaData.flatMap(_.incomingItemsCount),
                        lastUpdateTime = DateTime.now(),
                        logs = SortedSet(
                          Log(
                            time = DateTime.now(),
                            content =
                              s"Filtered a ${link("job", jobLink(userData))} from '${companyNameField(userData)}' for not containing a whitelisted word"
                          )
                        )
                      )
                    ),
                    templateInstructions = None
                  )
              }

            case None => reelElement
          }

        case reelElement => reelElement
      }

  def filterGermanLanguageLevel[A](
      targetField     : A => Option[String],
      maxGermanLevel  : GermanLanguageLevel,
      companyNameField: A => String,
      jobLink         : A => String
  )(using
      ExecutionContext,
      AIConfig,
      WebSocketStreamBackend[Future, PekkoStreams],
      Ordering[Log]
  ): Flow[
    ReelElement[A, JobMetaData, Instructions],
    ReelElement[A, JobMetaData, Instructions],
    NotUsed
  ] =
    Flow[ReelElement[A, JobMetaData, Instructions]]
      .mapAsync(1) {
        case reelElement @ ReelElement(Some(userData), userMetaData, _) =>
          targetField(userData) match {
            case None =>
              Future.successful(reelElement)
            case Some(fieldValue) =>
              LanguageDetection
                .isGerman(fieldValue)
                .flatMap {
                  case Left(error) => throw new RuntimeException(error)
                  case Right(true) =>
                    GermanLevelDetection
                      .process(fieldValue)
                      .map {
                        case None =>
                          reelElement
                        case Some(germanLevel: GermanLevelResponse)
                            if germanLevel.germanLevel.forall(
                              _.level <= maxGermanLevel.level
                            ) =>
                          reelElement
                        case x =>
                          ReelElement[A, JobMetaData, Instructions](
                            userData = None,
                            userMetaData = Some(
                              JobMetaData(
                                incomingItemsCount =
                                  userMetaData.flatMap(_.incomingItemsCount),
                                lastUpdateTime = DateTime.now(),
                                logs = SortedSet(
                                  Log(
                                    time = DateTime.now(),
                                    content =
                                      s"Filtered a ${link("job", jobLink(userData))} from '${companyNameField(userData)}' for not matching german language level(${x.flatMap(_.germanLevel).mkString})"
                                  )
                                )
                              )
                            ),
                            templateInstructions = None
                          )
                      }
                  case Right(false) => Future.successful(reelElement)
                }
          }
        case reelElement => Future.successful(reelElement)

      }

  def filterLanguages[A](
      targetField     : A => Option[String],
      allowedLanguages: Seq[String],
      companyNameField: A => String,
      jobLink         : A => String
  )(using
      ExecutionContext,
      AppConfig,
      Ordering[Log]
  ): Flow[
    ReelElement[A, JobMetaData, Instructions],
    ReelElement[A, JobMetaData, Instructions],
    NotUsed
  ] =
    Flow[ReelElement[A, JobMetaData, Instructions]]
      .mapAsync(1) {
        case reelElement @ ReelElement(Some(userData), userMetaData, _) =>
          targetField(userData) match
            case None => Future.successful(reelElement)
            case Some(fieldValue) =>
              given AIConfig = summon[AppConfig].ai
              LanguageDetection
                .process(fieldValue)
                .map {
                  case Left(error) => throw new RuntimeException(error)
                  case Right(languageDetectionResponse) =>
                    languageDetectionResponse.languages match
                      case Nil => reelElement
                      case languages
                          if languages.exists(allowedLanguages.contains) =>
                        reelElement
                      case _ =>
                        ReelElement[A, JobMetaData, Instructions](
                          userData = None,
                          userMetaData = Some(
                            JobMetaData(
                              incomingItemsCount =
                                userMetaData.flatMap(_.incomingItemsCount),
                              lastUpdateTime = DateTime.now(),
                              logs = SortedSet(
                                Log(
                                  time = DateTime.now(),
                                  content =
                                    s"Filtered a ${link("job", jobLink(userData))} from '${companyNameField(userData)}' for not using a whitelisted language"
                                )
                              )
                            )
                          ),
                          templateInstructions = None
                        )

                }

        case reelElement => Future.successful(reelElement)

      }

}
