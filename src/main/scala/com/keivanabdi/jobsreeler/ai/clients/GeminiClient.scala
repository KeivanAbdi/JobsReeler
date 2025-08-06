package com.keivanabdi.jobsreeler.ai.clients

import sttp.capabilities.pekko.PekkoStreams
import sttp.client4.*
import sttp.model.Header

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import com.keivanabdi.jobsreeler.ai.clients.GeminiClient
import com.keivanabdi.jobsreeler.ai.clients.GeminiClient.*
import com.keivanabdi.jobsreeler.models.config.AIConfig
import io.circe.*
import io.circe.Decoder
import io.circe.generic.auto.*
import io.circe.parser.*
import io.circe.syntax.*
import org.slf4j.LoggerFactory

trait GeminiClient[+OutputType](using Decoder[OutputType]) {
  type InputType

  private val logger = LoggerFactory.getLogger(getClass.getName)

  protected def promptGenerator(input: InputType): String

  def modelName(using aiConfig: AIConfig): String

  def process(input: InputType)(using
      ec                    : ExecutionContext,
      wsClientBackendOptions: WebSocketStreamBackend[Future, PekkoStreams],
      aiConfig              : AIConfig
  ): Future[Option[OutputType]] = {

    val promptText = promptGenerator(input)
    val requestBodyJson =
      GeminiRequestBody(
        contents = Seq(Content(Seq(Part(promptText))))
      ).asJson

    basicRequest
      .post(
        uri"https://generativelanguage.googleapis.com/v1beta/models/${modelName}:generateContent?key=${aiConfig.gemini.token}"
      )
      .withHeaders(Seq(Header("Content-Type", "application/json")))
      .body(requestBodyJson.noSpaces)
      .send(wsClientBackendOptions)
      .map { response =>
        logger.debug(response.toString)
        for {
          body           <- response.body.toOption
          geminiResponse <- decode[GeminiResponse](body).toOption
          textToDecode    = geminiResponse.candidates.head.content.parts.head.text
          output <- decode[OutputType](textToDecode).toOption.orElse {
                      logger.error(
                        s"Failed to decode OutputType from: $textToDecode"
                      )
                      None
                    }
        } yield {
          logger.info(
            s"Successfully processed input: $input, and got the result: $output"
          )
          output
        }
      }

  }

}

object GeminiClient {
  case class Part(text: String)
  case class Content(parts: Seq[Part])
  case class GeminiRequestBody(contents: Seq[Content])

  final case class GeminiResponseCandidate(content: Content)

  final case class GeminiResponse(candidates: Seq[GeminiResponseCandidate])

}
