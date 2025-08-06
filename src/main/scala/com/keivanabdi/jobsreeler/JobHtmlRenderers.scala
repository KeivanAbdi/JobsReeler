package com.keivanabdi.jobsreeler

import scalatags.Text.TypedTag
import scalatags.Text.all._
import scalatags.Text.tags2.{details, summary}

import com.keivanabdi.datareeler.templates.FullWidthInfiniteScroll
import com.keivanabdi.jobsreeler.models.job.JobDetail
import com.keivanabdi.jobsreeler.models.job.JobMetaData

object JobHtmlRenderers {

  def createExpandableItemHtml(jobDetail: JobDetail): TypedTag[String] = {
    def jobTypeHtml = jobDetail.summary.jobType match {
      case Some(jobType) => span(cls := "job-type")(jobType.name)
      case None          => frag()
    }

    val verifiedBadge =
      if (jobDetail.summary.isVerified)
        span(
          cls        := "badge verified-badge",
          aria.label := "Verified job posting"
        )("✓")
      else
        frag()

    val easyApplyBadge =
      if (jobDetail.summary.easyApply)
        span(cls := "badge easy-apply-badge")("Easy Apply")
      else
        frag()

    val location =
      s"${jobDetail.summary.location.major} ${jobDetail.summary.location.minor.mkString}"

    details(
      summary(
        div(cls := "summary-content")(
          div(cls := "main-info")(
            div(cls := "title-row")(
              a(
                cls    := "job-title",
                href   := jobDetail.summary.link,
                target := "_blank"
              )(
                jobDetail.summary.title
              ),
              jobTypeHtml,
              verifiedBadge,
              easyApplyBadge
            ),
            p(cls := "company")(jobDetail.summary.company),
            p(cls := "company-location")(location)
          )
        )
      ),
      div(cls := "content")(raw(jobDetail.descriptionHtml))
    )
  }

  def createMetaHtml(
      metaData: JobMetaData,
      maybePreviousTemplateInstructions: Option[
        FullWidthInfiniteScroll.Instructions
      ]
  ): TypedTag[String] = {

    div(cls := "meta-container")(
      div(cls := "logs-section")(
        metaData.logs.toSeq.reverse.map { x =>
          div(cls := "log-item")(
            span(cls := "time")(x.time.toString()),
            span(s": "),
            raw(x.content)
          )
        }
      ),
      div(cls := "stats-section")(
        div(cls := "stat-item")(
          span(
            s"Input stream items count: ${metaData.incomingItemsCount.getOrElse(0)}"
          )
        ),
        div(cls := "stat-item")(
          span(
            s"Output stream items count: ${metaData.outputItemsCount.getOrElse(0)}"
          )
        ),
        div(cls := "stat-item")(
          span(
            s"Unconsumed demanded items count: ${maybePreviousTemplateInstructions.map(_.pendingDemands).getOrElse(0)}"
          )
        ),
        div(cls := "stat-item")(
          span(
            "Last update time: ",
            tag("time")(
              id               := "timestamp",
              attr("datetime") := metaData.lastUpdateTime.toString
            )("0 seconds ago")
          )
        )
      )
    )
  }

}
