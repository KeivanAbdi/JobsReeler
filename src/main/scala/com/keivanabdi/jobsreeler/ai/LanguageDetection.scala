package com.keivanabdi.jobsreeler.ai

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import com.keivanabdi.jobsreeler.ai.LanguageDetection.LanguageDetectionValidResponse
import com.keivanabdi.jobsreeler.ai.clients.OllamaClient
import com.keivanabdi.jobsreeler.models.config.AIConfig
import io.circe.*
import io.circe.generic.auto.*
import io.github.ollama4j.utils.Options
import io.github.ollama4j.utils.OptionsBuilder

object LanguageDetection extends OllamaClient[LanguageDetectionValidResponse] {

  final case class LanguageDetectionValidResponse(
      languages: Seq[String]
  )

  type InputType = String

  def modelName(using aiConfig: AIConfig): String =
    aiConfig.languageDetection.model

  val ollamaOptions: Options =
    new OptionsBuilder()
      .setTemperature(0.0)
      .setTopK(40)
      .setTopP(0.9f)
      .build()

  protected def promptGenerator(input: InputType): String =
    s"""
       |You are a specialized Language Identification Bot.
       |Your ONLY task is to identify languages from the 'Text to Analyze' and output a specific JSON.
       |
       |Instructions:
       |1. Examine the 'Text to Analyze' provided below.
       |2. Identify which of the following languages are present: english, spanish, french, german, chinese, japanese, russian, portuguese, hindi, dutch.
       |3. If you identify Dutch, you MUST use the string "dutch".
       |
       |CRITICAL OUTPUT REQUIREMENTS (NON-NEGOTIABLE):
       |A. OUTPUT MUST BE JSON: Your entire response MUST be a single, raw, valid JSON object.
       |B. JSON STRUCTURE:
       |   - The JSON object MUST have EXACTLY ONE key: "languages".
       |   - The value for "languages" MUST be a list of strings.
       |   - Each string in the list MUST be one of the allowed languages (see instruction 2), in lowercase.
       |   - Example: {"languages": ["english", "french"]}
       |C. NO OTHER KEYS: DO NOT include ANY other keys in the JSON (e.g., no "job_title", "entities", "summary", etc.). ONLY "languages".
       |D. NO EXTRA TEXT: There MUST be NO text, explanations, apologies, or markdown (like ```json) before the opening '{' or after the closing '}'.
       |E. UNIDENTIFIED/GIBBERISH: If no listed languages are found, or the text is nonsensical, output: {"languages": []}
       |
       |Examples:
       |Input: "Bonjour, comment ça va?"
       |Output: {"languages": ["french"]}
       |
       |Input: "Hallo, hoe gaat het?"
       |Output: {"languages": ["german", "dutch"]}
       |
       |Input: "Goedendag"
       |Output: {"languages": ["dutch"]}
       |
       |Input: "This is a test."
       |Output: {"languages": ["english"]}
       |
       |Input: "Grazie mille"
       |Output: {"languages": []}
       |
       |Input: "你好，世界"
       |Output: {"languages": ["chinese"]}
       |
       |Input: "qwerty uiop"
       |Output: {"languages": []}
       |
       |Text to Analyze:
       |'''
       |$input
       |'''
       |
       |Based on the rules above, provide ONLY the JSON object.
       |The JSON object must start with '{' and end with '}'.
       |The JSON object must only contain the "languages" key.
       |JSON Output:
       |""".stripMargin

  def isGerman(input: InputType)(using
      ExecutionContext,
      AIConfig
  ): Future[Either[String, Boolean]] =
    process(input).map(_.map(_.languages.contains("german")))

}
