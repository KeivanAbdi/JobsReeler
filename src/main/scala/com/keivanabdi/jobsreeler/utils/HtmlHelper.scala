package com.keivanabdi.jobsreeler.utils

object HtmlHelper {

  def link(text: String, url: String) = {
    import scalatags.Text.all._
    a(href := url, target := "_blank")(text).render
  }

}
