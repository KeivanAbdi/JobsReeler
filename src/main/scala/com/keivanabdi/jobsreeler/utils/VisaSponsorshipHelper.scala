package com.keivanabdi.jobsreeler.utils

import sttp.capabilities.pekko.PekkoStreams
import sttp.client4.*
import sttp.client4.WebSocketStreamBackend
import sttp.model.Uri

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

import io.circe.parser.*

object VisaSponsorshipHelper {

  private def visaSponsorshipCompanies(country: String): Uri =
    uri"https://raw.githubusercontent.com/SiaExplains/visa-sponsorship-companies/refs/heads/main/countries/$country.json"

  private val linkedinProfileUrl =
    raw"""https:\/\/www.linkedin.com\/[^\/]+\/([^\/]*).*""".r

  def findVisaSponsoringCompanyUsernames(
      country: String
  )(using
      ec                    : ExecutionContext,
      wsClientBackendOptions: WebSocketStreamBackend[Future, PekkoStreams]
  ): Future[Either[String, Set[String]]] = {
    basicRequest
      .get(visaSponsorshipCompanies(country))
      .send(wsClientBackendOptions)
      .map { response =>
        response.body match
          case Right(jsonString) =>
            val companyLinkedinProfileUrls: Either[String, Vector[String]] =
              parse(jsonString) match
                case Left(error) =>
                  Left(error.toString())
                case Right(json) =>
                  json.asArray match
                    case None =>
                      Left("Json is in wrong format")
                    case Some(jsonArray) =>
                      Right(
                        jsonArray
                          .map(_ \\ "linkedin")
                          .flatMap(
                            _.flatMap(
                              _.asString.map(
                                _.replace(
                                  "_",
                                  "-"
                                )
                              )
                            )
                          )
                      )

            companyLinkedinProfileUrls
              .map {
                _.view
                  .collect { case linkedinProfileUrl(linkedinUsername) =>
                    linkedinUsername
                  }
                  .toSet
              }
          case Left(errorBody) =>
            Left(s"Failed to fetch data: $errorBody")
      }
      .recover { case NonFatal(e) =>
        Left(s"An error occurred during HTTP request: ${e.getMessage}")
      }
  }

}
