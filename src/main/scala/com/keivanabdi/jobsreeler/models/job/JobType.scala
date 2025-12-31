package com.keivanabdi.jobsreeler.models.job

import pureconfig.generic.derivation.EnumConfigReader

enum JobType(val name: String, val code: Int) derives EnumConfigReader:
  case Hybrid extends JobType("Hybrid", 3)
  case OnSite extends JobType("On-site", 1)
  case Remote extends JobType("Remote", 2)

object JobType:

  def withName(name: String): Option[JobType] =
    values.find(_.name.equals(name))
