package com.keivanabdi.jobsreeler.models.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.*

final case class AppConfig(
    ai           : AIConfig,
    httpProxy    : HttpProxyConfig,
    cookie       : CookieConfig,
    cache        : CacheConfig,
    sourceProfile: SourceProfileConfig
)

object AppConfig {
  given ConfigReader[AppConfig] = deriveReader
}
