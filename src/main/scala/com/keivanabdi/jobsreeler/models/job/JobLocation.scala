package com.keivanabdi.jobsreeler.models.job

final case class JobLocation(
    major: String,
    minor: Option[String]
) {

  override def toString(): String =
    Seq(Some(major), minor).flatten.mkString(" - ")

}
