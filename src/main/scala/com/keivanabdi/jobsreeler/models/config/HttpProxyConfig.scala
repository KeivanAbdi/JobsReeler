package com.keivanabdi.jobsreeler.models.config

import sttp.client4.BackendOptions

final case class HttpProxyConfig(
    host: String,
    port: Int
) {

  def asBackendOptions: BackendOptions =
    BackendOptions
      .httpProxy(host, port)

  override def toString(): String =
    s"http://$host:$port"

}
