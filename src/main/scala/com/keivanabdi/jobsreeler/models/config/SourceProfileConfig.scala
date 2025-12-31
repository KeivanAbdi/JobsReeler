package com.keivanabdi.jobsreeler.models.config

import com.keivanabdi.jobsreeler.streams.*
import pureconfig.ConfigReader
import pureconfig.generic.derivation.default.*
import com.keivanabdi.jobsreeler.ai.GermanLevelDetection.GermanLanguageLevel
import com.keivanabdi.jobsreeler.models.job.JobType

sealed trait SourceProfileConfig(
    val profile: StreamProfile
) derives ConfigReader {
  val searchKeywords: Set[String]
  val jobTypes      : Set[JobType]

}

sealed trait DACHJobsConfig extends SourceProfileConfig {

  val whitelistWords     : Set[String]
  val blacklistWords     : Set[String]
  val germanLanguageLevel: GermanLanguageLevel
  override def toString(): String =
    s"""
     whitelistWords: ${whitelistWords.mkString(", ")}
     blacklistWords: ${blacklistWords.mkString(", ")}
     """.stripIndent()
}

case class GermanyJobs(
    override val whitelistWords     : Set[String],
    override val blacklistWords     : Set[String],
    override val germanLanguageLevel: GermanLanguageLevel,
    override val jobTypes           : Set[JobType],
    override val searchKeywords     : Set[String]
) extends SourceProfileConfig(GermanyJobsStreamProfile)
    with DACHJobsConfig

case class AustriaJobs(
    override val whitelistWords     : Set[String],
    override val blacklistWords     : Set[String],
    override val germanLanguageLevel: GermanLanguageLevel,
    override val jobTypes           : Set[JobType],
    override val searchKeywords     : Set[String]
) extends SourceProfileConfig(AustriaJobsStreamProfile)
    with DACHJobsConfig
