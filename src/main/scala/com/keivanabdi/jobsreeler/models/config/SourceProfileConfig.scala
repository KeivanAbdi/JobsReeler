package com.keivanabdi.jobsreeler.models.config

import com.keivanabdi.jobsreeler.streams.*

sealed abstract class SourceProfileConfig(val profile: StreamProfile)

case object GermanyScalaJobs
    extends SourceProfileConfig(GermanyScalaJobsStreamProfile)

case object AustriaScalaJobs
    extends SourceProfileConfig(AustriaScalaJobsStreamProfile)
