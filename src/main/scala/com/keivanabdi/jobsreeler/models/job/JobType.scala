package com.keivanabdi.jobsreeler.models.job

enum JobType(val name: String):
  case Hybrid extends JobType("Hybrid")
  case OnSite extends JobType("On-site")
  case Remote extends JobType("Remote")

object JobType:

  def withName(name: String): Option[JobType] =
    values.find(_.name.equals(name))
