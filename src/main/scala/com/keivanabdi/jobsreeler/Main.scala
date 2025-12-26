package com.keivanabdi.jobsreeler

import models.config.AppConfig
import models.job.CookieItem
import models.job.JobMetaData
import models.job.Log
import pureconfig.*
import pureconfig.ConfigSource
import scrapers.LinkedInJobsScrapper
import sttp.capabilities.pekko.PekkoStreams
import sttp.client4.BackendOptions
import sttp.client4.WebSocketStreamBackend
import sttp.client4.pekkohttp.PekkoHttpBackend

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Source

import scala.collection.immutable.SortedSet
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*

import scala.io.StdIn

import com.keivanabdi.datareeler.models.*
import com.keivanabdi.datareeler.system.ReelerSystem
import com.keivanabdi.datareeler.templates.FullWidthInfiniteScroll
import com.keivanabdi.datareeler.templates.FullWidthInfiniteScroll.Instructions
import com.keivanabdi.jobsreeler.models.*
import com.keivanabdi.jobsreeler.models.config.CacheConfig
import com.keivanabdi.jobsreeler.models.job.JobDetail
import com.keivanabdi.jobsreeler.streams.StreamProfile
import com.keivanabdi.jobsreeler.utils.Cache
import io.circe.*
import io.circe.generic.auto.*
import io.circe.parser.*
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import java.io.File
import scala.util.Using

object Main {
  val logger = LoggerFactory.getLogger(getClass.getName)

  def main(args: Array[String]): Unit =
    args.collectFirst {
      case s"--source-profile=$filePath" if new File(filePath).exists() =>
        Right(ConfigSource.file(filePath))

      case s"--source-profile=$filePath" =>
        Left(s"The source profile file at '$filePath' does not exist.")

    } match
      case None =>
        logger.error(
          "No source profile provided. Please use --source-profile=<file-path> to specify the source profile configuration."
        )
      case Some(Left(error)) =>
        logger.error(error)

      case Some(Right(sourceProfileConfig)) =>
        sourceProfileConfig
          .withFallback(ConfigSource.default)
          .load[AppConfig] match
          case Left(errors) =>
            errors.toList.foreach(error => logger.error(error.description))
          case Right(appConfig) =>
            runServer(appConfig)

  def runServer(appConfig: AppConfig): Unit = {
    given AppConfig = appConfig

    given actorSystem: ActorSystem = ActorSystem("linkedin-scrapper")

    given Ordering[Log] = Ordering.by(_.time)

    given blockingEc: ExecutionContext =
      actorSystem.dispatchers.lookup("blocking-io-dispatcher")

    given wsClientBackendOptions: WebSocketStreamBackend[Future, PekkoStreams] =
      PekkoHttpBackend.usingActorSystem(
        actorSystem = actorSystem,
        options     = appConfig.httpProxy.asBackendOptions
      )

    given CacheConfig = appConfig.cache
    given Cache       = Cache()
    val cookieMapEither: Either[Error, Seq[CookieItem]] =
      Using(
        scala.io.Source.fromFile(appConfig.cookie.cookieFilePath)
      ) { source =>
        source.mkString
      }.toEither match {
        case Left(ex) =>
          Left(
            ParsingFailure(s"Failed to read cookie file: ${ex.getMessage}", ex)
          )
        case Right(content) =>
          parse(content).flatMap(_.as[Seq[CookieItem]])
      }

    cookieMapEither match
      case Left(error) => logger.error(error.toString())
      case Right(cookieItems) =>
        val streamProfile: StreamProfile =
          appConfig.sourceProfile.profile

        val linkedInScrapper: LinkedInJobsScrapper =
          new LinkedInJobsScrapper(
            cookieItems = cookieItems,
            initialUrl  = streamProfile.initialUrl,
            proxyServer = Some(appConfig.httpProxy.toString())
          )
        def inputStream: Source[
          ReelElement[JobDetail, JobMetaData, Instructions],
          ?
        ] =
          streamProfile.buildJobStream(linkedInScrapper)

        val reelerTemplate: FullWidthInfiniteScroll[JobDetail, JobMetaData] =
          FullWidthInfiniteScroll(
            dataHtmlRenderer = { _ =>
              JobHtmlRenderers.createExpandableItemHtml.andThen(
                HtmlRenderable.ScalatagsHtml(_)
              )
            },
            metaHtmlRenderer = {
              (previousTemplateInstruction: Option[Instructions]) =>
                (metaData: JobMetaData) =>
                  HtmlRenderable.ScalatagsHtml {
                    JobHtmlRenderers.createMetaHtml(
                      metaData = metaData,
                      maybePreviousTemplateInstructions =
                        previousTemplateInstruction
                    )
                  }
            },
            styleBlocks =
              CssRenderable.CssFile("/static/web/job/index.css") :: Nil,
            javascriptBlocks = JavascriptRenderable.JavascriptFile(
              "/static/web/job/index.js"
            ) :: Nil,
            defaultButtonText = "Click to enqueue more data calls!",
            previouslyRequestedItemsProcessedText =
              "Click to enqueue more data calls! [Your previously requested items were processed]",
            previouslyRequestedItemsNotProcessedText =
              "Click to enqueue more data calls! [Your previously requested items are enqueued for processing]",
            sendingSignalText              = "Sending fetching signals...",
            sendingSignalAnimationDuration = 900,
            updatingButtonTextDuration     = 300,
            streamFinishedText             = "I'm done"
          )

        val reelerSystem: ReelerSystem[
          JobDetail,
          JobMetaData,
          FullWidthInfiniteScroll.Instructions
        ] = {
          // Create the configuration object first
          val reelerConfig = ReelerSystemConfig(
            reelerTemplate = reelerTemplate,
            initialMetaData = () =>
              JobMetaData(
                incomingItemsCount = Some(0),
                lastUpdateTime     = DateTime.now(),
                logs               = SortedSet.empty
              ),
            demandBatchSize = 5,
            timeout         = 30.seconds
          )

          // Instantiate ReelerSystem with the config object
          ReelerSystem(
            inputReelElementStream = () => inputStream,
            config                 = reelerConfig,
            userRoutes             = Nil
          )
        }

        val bindingFuture = reelerSystem.start()

        bindingFuture
          .map { binding =>
            logger.info(
              s"Server online at http://localhost:8080/\nPress RETURN to stop..."
            )
            StdIn.readLine()

            logger.info("Stopping...")

            binding
              .unbind() // trigger unbinding from the port
              .onComplete(_ =>
                actorSystem.terminate()
              ) // and shutdown when done

          }
  }

}
