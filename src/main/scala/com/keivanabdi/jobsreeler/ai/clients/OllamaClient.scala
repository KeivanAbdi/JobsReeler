package com.keivanabdi.jobsreeler.ai.clients

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.blocking
import scala.util.chaining.*

import com.keivanabdi.jobsreeler.models.config.AIConfig
import io.circe.*
import io.circe.parser.*
import io.github.ollama4j.OllamaAPI
import io.github.ollama4j.utils.Options
import org.slf4j.LoggerFactory

trait OllamaClient[+OutputType](using Decoder[OutputType]) {
  type InputType

  private val logger = LoggerFactory.getLogger(getClass.getName)

  protected def promptGenerator(input: InputType): String

  def ollamaOptions: Options

  def modelName(using aiConfig: AIConfig): String

  def process(input: InputType)(using
      ec      : ExecutionContext,
      aiConfig: AIConfig
  ): Future[Either[String, OutputType]] =
    Future {
      blocking {
        val ollamaAPI: OllamaAPI =
          OllamaAPI(aiConfig.ollama.server)

        ollamaAPI.setVerbose(false)

        ollamaAPI.setRequestTimeoutSeconds(aiConfig.ollama.timeoutInSeconds)

        val response = ollamaAPI
          .generate(
            modelName,
            promptGenerator(input),
            false,
            ollamaOptions
          )
          .getResponse()
          .trim()
          .pipe {
            _.replaceAll("(?s)<think>.*</think>", "")
          }
        logger.debug(response)
        decode[OutputType](response)
      }
    }.map {
      case Left(error) =>
        logger.error(s"Failed to decode response for input: $input", error)
        Left(error.toString)
      case Right(value) =>
        logger.info(
          s"Successfully processed input: $input, and got the result: $value"
        )
        Right(value)
    }

}
