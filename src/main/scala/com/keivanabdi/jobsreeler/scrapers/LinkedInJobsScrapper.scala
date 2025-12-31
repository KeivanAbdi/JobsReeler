package com.keivanabdi.jobsreeler.scrapers

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.ThrottleMode
import org.apache.pekko.stream.scaladsl.Source

import java.util.function.BooleanSupplier
import java.util.function.Consumer
import scala.collection.immutable.SortedSet
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.blocking
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Random
import scala.util.Try
import scala.util.chaining.*

import com.keivanabdi.datareeler.models.ReelElement
import com.keivanabdi.datareeler.templates.FullWidthInfiniteScroll.Instructions
import com.keivanabdi.jobsreeler.models.job.*
import com.keivanabdi.jobsreeler.models.job.CookieItem
import com.keivanabdi.jobsreeler.scrapers.JobDetailFetcher
import com.keivanabdi.jobsreeler.utils.Cache
import com.microsoft.playwright.*
import com.microsoft.playwright.Browser.NewContextOptions
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Page.NavigateOptions
import com.microsoft.playwright.Page.WaitForConditionOptions
import com.microsoft.playwright.Page.WaitForSelectorOptions
import com.microsoft.playwright.options.Cookie
import com.microsoft.playwright.options.WaitForSelectorState
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.slf4j.spi.LoggingEventBuilder

case class NavigationException(msg: String, cause: Throwable = null)
    extends RuntimeException(msg, cause)

class LinkedInJobsScrapper(
    cookieItems: Seq[CookieItem],
    initialUrl : String,
    proxyServer: Option[String]
)(using
    ec      : ExecutionContext,
    ordering: Ordering[Log],
    cache   : Cache
) {
  val logger = LoggerFactory.getLogger(getClass.getName)

  // --- Constants ---
  final private val UserAgentString =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

  final private val DefaultTimeoutMillis = 1500000
  final private val ScrollIncrementPx    = 100

  // Selectors
  final private val JobListContainerSelector = ".scaffold-layout__list > div"

  final private val JobListItemSelector =
    ".scaffold-layout__list-item:not(.jobs-search-results__list-item--inline-suggestion)"

  final private val JobIdElementSelector  = "div[data-job-id]"
  final private val FeedbackPanelSelector = ".jobs-list-feedback--fixed-width"
  final private val JobDetailDescriptionSelector =
    ".core-section-container__content"

  final private val NextPageButtonSelector =
    "ul[class*='_pages'] > li:is(.selected, :has(*[class*='active'])) + li:not(.selected):not(:has(*[class*='active'])) > button"

  private val jobDetailFetcher = new JobDetailFetcher(
    cookieItems                  = cookieItems,
    proxyServer                  = proxyServer,
    userAgentString              = UserAgentString,
    defaultTimeoutMillis         = DefaultTimeoutMillis,
    jobDetailDescriptionSelector = JobDetailDescriptionSelector,
    initialUrl                   = initialUrl
  )

  def log(
      logLevel  : Level,
      message   : String,
      logActor  : ActorRef,
      maybeCause: Option[Throwable] = None
  ): Unit =
    logger
      .atLevel(logLevel)
      .pipe { (logEventBuilder: LoggingEventBuilder) =>
        maybeCause match
          case None        => logEventBuilder
          case Some(cause) => logEventBuilder.setCause(cause)
      }
      .log(message)

    logActor !
      ReelElement[JobDetail, JobMetaData, Instructions](
        userData = None,
        userMetaData = Some(
          JobMetaData(
            incomingItemsCount = None,
            outputItemsCount   = None,
            lastUpdateTime     = DateTime.now,
            logs               = SortedSet(Log(DateTime.now, message))
          )
        ),
        templateInstructions = None
      )

  def jobDetail(jobSummary: JobSummary)(using ActorSystem): Future[JobDetail] =
    jobDetailFetcher.fetchJobDetail(jobSummary)

  /** Creates and returns a headless Chromium browser, optionally configured
    * with a proxy.
    */
  private def createBrowser(): Browser = {
    val playwright: Playwright = Playwright.create()
    val launchOptions = new BrowserType.LaunchOptions()
      .setHeadless(true)
      .setTimeout(DefaultTimeoutMillis)
      .pipe { self =>
        proxyServer match {
          case None        => self
          case Some(proxy) => self.setProxy(new options.Proxy(proxy))
        }
      }
    playwright.chromium().launch(launchOptions)
  }

  /** Creates a Playwright `Route` consumer to block most image requests,
    * allowing only specific company logos to load.
    */
  private def createRouter(): Consumer[Route] =
    new Consumer[Route] {
      override def accept(route: Route): Unit = {
        route.request().resourceType() match {
          case "image" =>
            if (route.request().url().contains("company-logo_100_100")) {
              route.resume()
            } else {
              route.abort()
            }
          case _ => route.resume()
        }
      }
    }

  /** Scrolls the page to stabilize the job list, ensuring all visible jobs are
    * loaded. Includes a random delay to mimic human interaction.
    *
    * @param page
    *   The Playwright `Page`.
    * @param logActor
    *   The `ActorRef` for logging.
    * @return
    *   The count of job summaries found after stabilization.
    */
  private def waitForJobListStabilization(
      page    : Page,
      logActor: ActorRef
  ): Int = {
    page.waitForSelector(JobListContainerSelector)
    val listLayout = page.locator(JobListContainerSelector)

    val targetJobCount = listLayout
      .locator(JobListItemSelector)
      .count()

    log(Level.INFO, s"Found $targetJobCount jobs in the new page", logActor)

    var count                = 0
    var lastLoggedPercentage = 0.0
    // Wait until the job list count changes from the initial count (might happen quickly)
    Try(
      page.waitForCondition(
        () =>
          listLayout.locator(JobIdElementSelector).count() != targetJobCount,
        new WaitForConditionOptions().setTimeout(0)
      )
    )
      .recover { exception =>
        log(
          Level.ERROR,
          s"Failed to properly load page",
          logActor,
          Some(exception)
        )
      }

    // Scroll until the job list count stabilizes at the target count
    page.waitForCondition(
      { () =>
        // Scroll down
        page.evaluate(
          // Use constant selector and value
          s"""document.querySelector("$JobListContainerSelector").scrollTop=${count * ScrollIncrementPx}"""
        )
        count += 1
        val currentCount = listLayout.locator(JobIdElementSelector).count
        val currentPercent: Double =
          (currentCount / targetJobCount.toDouble) * 100
        val currentRoundedPercent: Int = (currentPercent / 10).toInt * 10

        if (currentRoundedPercent != lastLoggedPercentage) {
          log(
            Level.INFO,
            s"Scrolling down to get all jobs on the page: %${currentRoundedPercent}",
            logActor
          )
          lastLoggedPercentage = currentRoundedPercent
        }

        Thread.sleep(
          Random.nextInt(750)
        ) // Random delay for human-like scrolling.

        currentCount == targetJobCount // Check if job count has stabilized.
      },
      new WaitForConditionOptions().setTimeout(0)
    )
    targetJobCount
  }

  /** Extracts `JobSummary` objects from the current page. */
  private def extractJobSummaries(page: Page): Source[JobSummary, NotUsed] = {
    val jobElements = page.locator(JobIdElementSelector).all().asScala.toList
    Source(jobElements).map(elem => JobSummary.from(page, elem))
  }

  /** Processes the page to extract job summaries after stabilization.
    *
    * @param withPanelCheck
    *   If true, waits for a feedback panel (used for initial page load).
    * @return
    *   A tuple of `Source[JobSummary, ?]` and a `Boolean` indicating if jobs
    *   were found.
    */
  private def processPage(
      page          : Page,
      withPanelCheck: Boolean,
      logActor      : ActorRef
  ): (Source[JobSummary, ?], Boolean) = {
    val result = Try {
      if (withPanelCheck) {
        if (page.content().contains("There was a problem")) {
          throw new RuntimeException("Page load error")
        } else {
          page.waitForSelector(
            FeedbackPanelSelector,
            new WaitForSelectorOptions()
              .setState(WaitForSelectorState.ATTACHED)
              .setTimeout(DefaultTimeoutMillis)
          )
        }
      }
    }

    result
      .map { _ =>
        val targetJobCount = waitForJobListStabilization(page, logActor)
        val summaries      = extractJobSummaries(page)
        summaries -> (targetJobCount > 0)
      }
      .recover { case e =>
        log(
          Level.ERROR,
          "There was a problem in loading the page. Please refresh the cookie values if the issue persists",
          logActor,
          Some(e)
        )
        Source.failed(e) -> false
      }
      .get
  }

  /** Navigates to the initial URL, handles response status, and processes the
    * page. Throws `NavigationException` on failure.
    *
    * @return
    *   A `Future` with `Source[JobSummary, ?]` and a `Boolean` (non-empty
    *   results).
    */
  private def setupInitialNavigation(
      page    : Page,
      logActor: ActorRef
  ): Future[(Source[JobSummary, ?], Boolean)] =
    Future {
      blocking {

        // 1) Navigate and get the response
        val response: Response =
          try {
            page.navigate(
              initialUrl,
              new NavigateOptions().setTimeout(DefaultTimeoutMillis)
            )
          } catch {
            case e: PlaywrightException =>
              Try(page.close())

              throw NavigationException(
                s"Initial navigation to '$initialUrl' failed: ${e.getMessage}",
                e
              )
          }

        log(Level.INFO, s"Navigated to '$initialUrl'", logActor)

        // 2) Check HTTP status
        if (response.status() != 200) {
          log(Level.ERROR, "Invalid http status from linkedin", logActor)

          page.close()
          throw NavigationException(
            s"Authentication failed or initial load error: status ${response.status()}"
          )
        }
        // 3) Process the page (will propagate any exception)
        processPage(
          page           = page,
          withPanelCheck = true,
          logActor       = logActor
        )
      }
    }

  /** Recursively handles pagination: clicks next page, processes content, and
    * continues if new jobs are found. Ends pagination if no next button or no
    * new jobs.
    *
    * @param page
    *   The Playwright `Page`.
    * @param logActor
    *   The `ActorRef` for logging.
    * @return
    *   A `Source` of `JobSummary` objects.
    */
  private def handlePagination(
      page    : Page,
      logActor: ActorRef
  ): Source[JobSummary, ?] = {
    Try(
      page
        .locator(NextPageButtonSelector)
        .first()
    ).toOption match {
      case Some(locator) if locator.isVisible() =>
        log(Level.INFO, "Clicking next page button.", logActor)

        locator
          .click(
            new Locator.ClickOptions()
              .setForce(true)
              .setTimeout(DefaultTimeoutMillis)
          )

        val (source, nonEmpty) =
          processPage(
            page           = page,
            withPanelCheck = false,
            logActor       = logActor
          )

        if (nonEmpty) {
          source.concatLazy(
            Source.lazySource(() => handlePagination(page, logActor))
          )
        } else {
          log(Level.WARN, "Pagination ended: no new items found.", logActor)
          source
        }
      case _ =>
        log(
          Level.ERROR,
          "No next page button found or visible; ending pagination.",
          logActor
        )
        Source.empty
    }
  }

  /** Main Akka Stream `Source` for scraping LinkedIn job summaries. Initializes
    * a headless browser, sets cookies, handles navigation, and manages
    * pagination. The stream is throttled and includes error recovery.
    *
    * @param logActor
    *   The `ActorRef` for logging.
    * @return
    *   An Akka Stream `Source` emitting `JobSummary` objects.
    */
  def jobsSummarySource(
      logActor: ActorRef
  ): Source[JobSummary, NotUsed] =
    Source
      .lazyFutureSource { () =>
        Future {
          blocking {
            val browser: Browser =
              createBrowser()

            log(
              Level.INFO,
              "Setting up headless browser for job scraping.",
              logActor
            )

            val context: BrowserContext =
              browser.newContext(
                new NewContextOptions().setUserAgent(UserAgentString)
              )

            context
              .addCookies(
                cookieItems
                  .map { case CookieItem(name, value) =>
                    new Cookie(name, value)
                      .setDomain("www.linkedin.com")
                      .setPath("/")
                      .setSecure(true)
                  }
                  .toList
                  .asJava
              )

            val page = context.newPage()
            page.route("**/*", createRouter())

            setupInitialNavigation(
              page     = page,
              logActor = logActor
            )
              .map { case (source, nonEmpty) =>
                if (nonEmpty)
                  source.concatLazy(
                    Source.lazySource(() => handlePagination(page, logActor))
                  )
                else source
              }
              .map {
                _.watchTermination() { (mat, done) =>
                  done.onComplete { _ =>
                    Try(page.close())
                    Try(context.close())
                    Try(browser.close())
                    log(
                      Level.INFO,
                      "Playwright browser, context, and page closed.",
                      logActor
                    )
                  }
                  mat
                }
              }
              .recover { case e =>
                log(
                  Level.ERROR,
                  s"Browser setup or navigation failed: ${e.getMessage}",
                  logActor,
                  Some(e)
                )
                Source.failed(e)
              }
          }
        }.flatMap(identity)
      }
      .mapMaterializedValue(_ => NotUsed)
      .throttle(
        elements     = 1,
        per          = 3.seconds,
        maximumBurst = 0,
        mode         = ThrottleMode.Shaping
      )

}
