package com.keivanabdi.jobsreeler.models.job

import scala.collection.immutable.SortedSet

import org.joda.time.DateTime

final case class JobMetaData(
    incomingItemsCount: Option[Long],
    outputItemsCount  : Option[Long] = None,
    lastUpdateTime    : DateTime,
    logs              : SortedSet[Log]
)
