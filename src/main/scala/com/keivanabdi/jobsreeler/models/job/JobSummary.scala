package com.keivanabdi.jobsreeler.models.job

import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import io.circe.Encoder
import io.circe.Json
import org.joda.time.LocalDate
import org.slf4j.LoggerFactory

final case class JobSummary(
    id             : String,
    title          : String,
    isVerified     : Boolean,
    company        : String,
    jobType        : Option[JobType],
    location       : JobLocation,
    createTime     : Option[LocalDate],
    companyLogo    : Option[String],
    companyUsername: Option[String],
    easyApply      : Boolean
) {
  def link: String = s"https://www.linkedin.com/jobs/view/$id/"
}

object JobSummary {

  private val logger = LoggerFactory.getLogger(getClass.getName)

  val usernameRegex: Regex =
    """.*\/(.*)_logo.*""".r

  val jobMetaDataPatternRegex: Regex =
    """(?:(.+?),\s*)?([^,\(\)]+?)(?:\s*\(([^\(\)]+)\))?$""".r

  def from(page: Page, jobElement: Locator): JobSummary =
    val jobId: String =
      jobElement.getAttribute("data-job-id")

    val isVerified: Boolean =
      jobElement
        .locator(".text-view-model__verified-icon")
        .count() > 0

    val title: String =
      jobElement
        .locator(".job-card-container__link>span>strong")
        .textContent()
        .trim()

    val company: String =
      jobElement
        .locator(".artdeco-entity-lockup__subtitle")
        .textContent()
        .trim()

    page.waitForSelector(".job-card-container__metadata-wrapper")

    val jobMetaDataRaw: String =
      jobElement
        .locator(
          ".job-card-container__metadata-wrapper"
        )
        .first()
        .textContent
        .trim()

    val dateRaw: Option[String] =
      jobElement
        .locator("time")
        .all()
        .asScala
        .headOption
        .map(_.getAttribute("datetime"))

    val easyApply: Boolean =
      jobElement
        .locator(
          ".job-card-list__footer-wrapper"
        )
        .textContent()
        .contains("Easy Apply")

    val companyLogo: Option[String] =
      jobElement
        .locator(".job-card-list__logo-ivm img")
        .all()
        .asScala
        .headOption
        .map(_.getAttribute("src"))

    val companyUsername: Option[String] =
      companyLogo
        .flatMap {
          case usernameRegex(username) =>
            Some(username.replace("_", "-"))
          case _ =>
            logger.warn(s"couldn't get company logo for $company $companyLogo")
            None
        }

    jobMetaDataRaw match {

      case jobMetaDataPatternRegex(
            minorRaw,
            major,
            jobTypeRaw
          ) =>
        JobSummary(
          id              = jobId,
          title           = title,
          isVerified      = isVerified,
          company         = company,
          jobType         = Option(jobTypeRaw).flatMap(JobType.withName),
          location        = JobLocation(major = major, minor = Option(minorRaw)),
          createTime      = dateRaw.map(LocalDate.parse),
          easyApply       = easyApply,
          companyLogo     = companyLogo,
          companyUsername = companyUsername
        )
      case _ =>
        ???

    }

  given Encoder[LocalDate] = new Encoder[LocalDate] {
    def apply(a: LocalDate): Json = Json.fromString(a.toString("yyyy-MM-dd"))
  }

  given Encoder[JobType] = new Encoder[JobType] {
    def apply(a: JobType): Json = Json.fromString(a.name)
  }

}
