package com.keivanabdi.jobsreeler.models.config

import pureconfig.ConfigReader
import pureconfig.generic.derivation.default.*

final case class AppConfig(
    ai           : AIConfig,
    httpProxy    : HttpProxyConfig,
    cookie       : CookieConfig,
    cache        : CacheConfig,
    sourceProfile: SourceProfileConfig
) derives ConfigReader
