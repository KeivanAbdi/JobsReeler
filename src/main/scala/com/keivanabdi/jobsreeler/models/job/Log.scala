package com.keivanabdi.jobsreeler.models.job

import org.joda.time.DateTime

final case class Log(
    time   : DateTime,
    content: String
)
