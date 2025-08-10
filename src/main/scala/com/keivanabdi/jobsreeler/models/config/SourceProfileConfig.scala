package com.keivanabdi.jobsreeler.models.config

import com.keivanabdi.jobsreeler.streams.*
import pureconfig.ConfigReader

sealed abstract class SourceProfileConfig(
    val profile       : StreamProfile,
    val whitelistWords: Set[String],
    val blacklistWords: Set[String]
) derives ConfigReader {
  override def toString(): String =
    s"""
    whitelistWords: ${whitelistWords.mkString(", ")}
    blacklistWords: ${blacklistWords.mkString(", ")}
    """.stripIndent()
}

case class GermanyScalaJobs(
    override val whitelistWords: Set[String],
    override val blacklistWords: Set[String]
) extends SourceProfileConfig(
      profile        = GermanyScalaJobsStreamProfile,
      whitelistWords = whitelistWords,
      blacklistWords = blacklistWords
    )

case class AustriaScalaJobs(
    override val whitelistWords: Set[String],
    override val blacklistWords: Set[String]
) extends SourceProfileConfig(
      profile        = AustriaScalaJobsStreamProfile,
      whitelistWords = whitelistWords,
      blacklistWords = blacklistWords
    )
