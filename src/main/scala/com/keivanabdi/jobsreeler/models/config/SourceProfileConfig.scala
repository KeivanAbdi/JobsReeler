package com.keivanabdi.jobsreeler.models.config

import com.keivanabdi.jobsreeler.streams.*
import pureconfig.ConfigReader
import pureconfig.generic.derivation.default.*
import com.keivanabdi.jobsreeler.ai.GermanLevelDetection.GermanLanguageLevel
import com.keivanabdi.jobsreeler.models.job.JobType

sealed trait SourceProfileConfig(
    val profile: StreamProfile
) derives ConfigReader

sealed trait DACHScalaJobsConfig extends SourceProfileConfig {

  val whitelistWords     : Set[String]
  val blacklistWords     : Set[String]
  val germanLanguageLevel: GermanLanguageLevel
  val jobTypes           : Set[JobType]
  override def toString(): String =
    s"""
     whitelistWords: ${whitelistWords.mkString(", ")}
     blacklistWords: ${blacklistWords.mkString(", ")}
     """.stripIndent()
}

case class GermanyScalaJobs(
    override val whitelistWords     : Set[String],
    override val blacklistWords     : Set[String],
    override val germanLanguageLevel: GermanLanguageLevel,
    override val jobTypes           : Set[JobType]
) extends SourceProfileConfig(GermanyScalaJobsStreamProfile)
    with DACHScalaJobsConfig

case class AustriaScalaJobs(
    override val whitelistWords     : Set[String],
    override val blacklistWords     : Set[String],
    override val germanLanguageLevel: GermanLanguageLevel,
    override val jobTypes           : Set[JobType]
) extends SourceProfileConfig(AustriaScalaJobsStreamProfile)
    with DACHScalaJobsConfig
