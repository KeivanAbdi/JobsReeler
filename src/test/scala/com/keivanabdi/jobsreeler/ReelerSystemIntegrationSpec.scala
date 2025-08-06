package com.keivanabdi.jobsreeler

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.http.scaladsl.model.HttpResponse
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.http.scaladsl.model.sse.ServerSentEvent
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.http.scaladsl.unmarshalling.sse.EventStreamUnmarshalling._
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.testkit.TestKit

import scala.collection.immutable.SortedSet
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._

import com.keivanabdi.datareeler.models.{
  CssRenderable,
  HtmlRenderable,
  JavascriptRenderable,
  ReelElement,
  ReelerSystemConfig
}
import com.keivanabdi.datareeler.models.RenderedReelElement
import com.keivanabdi.datareeler.system.ReelerSystem
import com.keivanabdi.datareeler.templates.FullWidthInfiniteScroll
import com.keivanabdi.datareeler.templates.FullWidthInfiniteScroll.Instructions
import com.keivanabdi.jobsreeler.models.job.{
  JobDetail,
  JobLocation,
  JobMetaData,
  JobSummary,
  Log
}
import io.circe.*
import io.circe.generic.auto.*
import io.circe.parser.*
import org.joda.time.DateTime
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ReelerSystemIntegrationSpec
    extends TestKit(ActorSystem("ReelerSystemIntegrationSpec"))
    with AnyWordSpecLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll {

  given materializer: Materializer = Materializer(system)
  given Ordering[Log] = Ordering.by(_.time)

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = 30.seconds, interval = 50.millis)

  private given ec: scala.concurrent.ExecutionContext = system.dispatcher

  val port      = 8000
  val interface = "localhost"

  def reelerTemplate: FullWidthInfiniteScroll[JobDetail, JobMetaData] =
    FullWidthInfiniteScroll(
      dataHtmlRenderer = _ =>
        JobHtmlRenderers.createExpandableItemHtml.andThen(
          HtmlRenderable.ScalatagsHtml(_)
        ),
      metaHtmlRenderer = {
        previousTemplateInstruction => (metaData: JobMetaData) =>
          HtmlRenderable.ScalatagsHtml(
            JobHtmlRenderers
              .createMetaHtml(metaData, previousTemplateInstruction)
          )
      },
      styleBlocks = CssRenderable.CssFile("/static/web/job/index.css") :: Nil,
      javascriptBlocks =
        JavascriptRenderable.JavascriptFile("/static/web/job/index.js") :: Nil,
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

  val reelerConfig = ReelerSystemConfig(
    reelerTemplate = reelerTemplate,
    initialMetaData = () =>
      JobMetaData(
        incomingItemsCount = Some(0),
        lastUpdateTime     = DateTime.now(),
        logs               = SortedSet.empty
      ),
    demandBatchSize = 10,
    timeout         = 10.seconds,
    interface       = interface,
    port            = port
  )

  def reelerSystem(
      stream: Source[
        ReelElement[
          JobDetail,
          JobMetaData,
          FullWidthInfiniteScroll.Instructions
        ],
        ?
      ]
  ): ReelerSystem[JobDetail, JobMetaData, Instructions] =
    ReelerSystem(
      inputReelElementStream = () => stream,
      config                 = reelerConfig,
      userRoutes             = Nil
    )

  val send: HttpRequest => Future[HttpResponse] = Http().singleRequest(_)

  "ReelerSystem" should {
    "behave as expected" in {
      lazy val stream
          : Source[ReelElement[JobDetail, JobMetaData, Instructions], ?] =
        Source.lazySource(() =>
          Source(1 to 10).map { i =>
            val jobSummary = JobSummary(
              id         = s"job-$i",
              title      = s"Software Engineer $i",
              isVerified = true,
              company    = s"Company $i",
              jobType    = None,
              location =
                JobLocation(major = s"City $i", minor = Some(s"State $i")),
              createTime      = Some(DateTime.now().toLocalDate),
              companyLogo     = None,
              companyUsername = None,
              easyApply       = true
            )
            val jobDetail = JobDetail(
              summary         = jobSummary,
              descriptionText = s"Description for job $i",
              descriptionHtml = s"<p>Description for job $i</p>"
            )
            val dummyMetaData = JobMetaData(
              incomingItemsCount = Some(i),
              outputItemsCount   = Some(i),
              lastUpdateTime     = DateTime.now(),
              logs               = SortedSet.empty
            )
            ReelElement(
              userData     = Some(jobDetail),
              userMetaData = Some(dummyMetaData),
              templateInstructions =
                Option.empty[FullWidthInfiniteScroll.Instructions]
            )
          }
        )

      val rs: ReelerSystem[JobDetail, JobMetaData, Instructions] =
        reelerSystem(stream)

      val eventualSseEvents =
        rs.start().flatMap { serverBinding =>

          val sseClientSource: Source[ServerSentEvent, NotUsed] = {
            val response =
              send(HttpRequest(uri = Uri(s"http://$interface:$port/events")))
            val source = response
              .flatMap(Unmarshal(_).to[Source[ServerSentEvent, NotUsed]])
            Source.futureSource(source).mapMaterializedValue(_ => NotUsed)
          }
          send(
            HttpRequest(uri = s"http://$interface:$port/load-more")
          ).futureValue

          val streamItemsFuture = sseClientSource
            .map(x => decode[RenderedReelElement](x.data))
            .collect {
              case Right(x) if !x.isEmpty => x
            }
            .toMat(Sink.seq)(Keep.right)
            .run()

          for {
            streamitems <- streamItemsFuture
            _           <- serverBinding.terminate(1.second)

          } yield streamitems

        }

      val streamitems: Seq[RenderedReelElement] =
        eventualSseEvents.futureValue

      val lastMeta: Option[String] =
        streamitems.collect { case RenderedReelElement(_, Some(meta), _) =>
          meta
        }.lastOption

      val data: Seq[String] =
        streamitems.collect { case RenderedReelElement(Some(data), _, _) =>
          data
        }

      val lastTemplateInstruction =
        streamitems.collect {
          case RenderedReelElement(_, _, Some(templateInstructions)) =>
            templateInstructions.toString
        }.lastOption

      lastMeta should not be empty
      lastMeta.get should include("Input stream items count: 10")
      lastMeta.get should include("Output stream items count: 10")
      lastMeta.get should include("Unconsumed demanded items count: 0")

      data.size shouldBe 10

      lastTemplateInstruction should not be empty

      lastTemplateInstruction.get should include("I'm done")

    }
  }

}
