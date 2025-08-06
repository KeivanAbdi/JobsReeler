package com.keivanabdi.jobsreeler.scrapers

import org.apache.pekko.actor.ActorSystem

import java.net.InetSocketAddress
import scala.concurrent.{ExecutionContext, Future}

import com.keivanabdi.jobsreeler.models.job.{CookieItem, JobDetail, JobSummary}
import com.keivanabdi.jobsreeler.utils.Cache
import com.keivanabdi.jobsreeler.utils.FutureDelays
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Document.OutputSettings
import org.jsoup.nodes.Entities.EscapeMode
import org.jsoup.safety.Safelist

class JobDetailFetcher(
    cookieItems                 : Seq[CookieItem],
    proxyServer                 : Option[String],
    userAgentString             : String,
    defaultTimeoutMillis        : Int,
    jobDetailDescriptionSelector: String,
    initialUrl                  : String
)(using ec: ExecutionContext, cache: Cache) {

  final private val DefaultHeaders: Map[String, String] = Map(
    "Referer" -> initialUrl
  )

  private def cleanPreserveLineBreaks(bodyHtml: String): String = {
    val prettyPrintedBodyFragment =
      Jsoup.clean(
        bodyHtml,
        "",
        Safelist.none().addTags("br", "p"),
        new OutputSettings().prettyPrint(true)
      );
    Jsoup.clean(
      prettyPrintedBodyFragment,
      "",
      Safelist.none(),
      new OutputSettings().prettyPrint(false)
    )
  }

  private def removeExtraElements(bodyHtml: String): String = {
    val doc: Document = Jsoup.parseBodyFragment(bodyHtml)
    doc.select("button").forEach { buttonEl =>
      buttonEl.remove()
    }
    doc.select(".find-a-referral__cta").forEach { referral =>
      referral.remove()
    }
    val settings = new OutputSettings()
      .prettyPrint(false)
      .escapeMode(EscapeMode.xhtml)
    doc.outputSettings(settings)
    doc.body().html()
  }

  private def addCookiesToConnection(conn: Connection): Connection = {
    cookieItems.foldLeft(conn) { case (c, CookieItem(name, value)) =>
      c.cookie(name, value)
    }
  }

  private def addHeadersToConnection(conn: Connection): Connection = {
    DefaultHeaders.foldLeft(conn) { case (c, (name, value)) =>
      c.header(name, value)
    }
  }

  def fetchJobDetail(
      jobSummary: JobSummary
  )(using ActorSystem): Future[JobDetail] = {
    cache
      .getOrElseUpdate(jobSummary.id) {
        for {
          result <- Future {
                      val url: String =
                        s"https://www.linkedin.com/jobs-guest/jobs/api/jobPosting/${jobSummary.id}"

                      val baseConn: Connection =
                        Jsoup
                          .connect(url)
                          .userAgent(userAgentString)
                          .timeout(defaultTimeoutMillis)

                      val proxiedConn = proxyServer match {
                        case None =>
                          baseConn
                        case Some(proxyServer) =>
                          val (serverPart, portPart) =
                            proxyServer.splitAt(proxyServer.lastIndexOf(":"))
                          baseConn.proxy(
                            new java.net.Proxy(
                              java.net.Proxy.Type.HTTP,
                              InetSocketAddress.createUnresolved(
                                serverPart.replaceAll("http(s)?://", ""),
                                portPart.drop(1).toInt
                              )
                            )
                          )
                      }
                      val connWithHeaders = addHeadersToConnection(proxiedConn)
                      val connWithCookies =
                        addCookiesToConnection(connWithHeaders)
                      val page = connWithCookies.get()
                      page.select(jobDetailDescriptionSelector).html()
                    }
          _ <- FutureDelays.delay(10000) // safety delay

        } yield result

      }
      .map { jobDescriptionHtml =>
        JobDetail(
          summary         = jobSummary,
          descriptionText = cleanPreserveLineBreaks(jobDescriptionHtml),
          descriptionHtml = removeExtraElements(jobDescriptionHtml)
        )
      }
  }

}
