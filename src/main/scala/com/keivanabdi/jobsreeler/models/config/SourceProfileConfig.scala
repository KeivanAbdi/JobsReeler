package com.keivanabdi.jobsreeler.models.config

import com.keivanabdi.jobsreeler.streams.*
import pureconfig.ConfigReader
import pureconfig.generic.derivation.default.*

sealed trait SourceProfileConfig(
    val profile: StreamProfile
) derives ConfigReader {
  val whitelistWords: Set[String]
  val blacklistWords: Set[String]
}

sealed trait DACHScalaJobsConfig extends SourceProfileConfig {

  override def toString(): String =
    s"""
     whitelistWords: ${whitelistWords.mkString(", ")}
     blacklistWords: ${blacklistWords.mkString(", ")}
     """.stripIndent()
}

case class GermanyScalaJobs(
    override val whitelistWords: Set[String],
    override val blacklistWords: Set[String]
) extends SourceProfileConfig(GermanyScalaJobsStreamProfile)
    with DACHScalaJobsConfig

case class AustriaScalaJobs(
    override val whitelistWords: Set[String],
    override val blacklistWords: Set[String]
) extends SourceProfileConfig(AustriaScalaJobsStreamProfile)
    with DACHScalaJobsConfig
