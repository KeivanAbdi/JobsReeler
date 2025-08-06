package com.keivanabdi.jobsreeler.models.job

final case class JobDetail(
    summary        : JobSummary,
    descriptionText: String,
    descriptionHtml: String
)
