package com.keivanabdi.jobsreeler.models.config

import com.keivanabdi.jobsreeler.streams.GermanScalaJobsStreamProfile
import com.keivanabdi.jobsreeler.streams.StreamProfile

sealed abstract class SourceProfileConfig(val profile: StreamProfile)

case object GermanScalaJobs
    extends SourceProfileConfig(GermanScalaJobsStreamProfile)
