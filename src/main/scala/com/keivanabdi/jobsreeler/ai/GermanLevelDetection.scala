package com.keivanabdi.jobsreeler.ai

import scala.util.Try

import com.keivanabdi.jobsreeler.ai.GermanLevelDetection.*
import com.keivanabdi.jobsreeler.ai.clients.GeminiClient
import com.keivanabdi.jobsreeler.models.config.AIConfig
import io.circe.*
import pureconfig.generic.derivation.EnumConfigReader

object GermanLevelDetection extends GeminiClient[GermanLevelResponse] {

  enum GermanLanguageLevel(val level: Int) derives EnumConfigReader:
    case A1 extends GermanLanguageLevel(1)
    case A2 extends GermanLanguageLevel(2)
    case B1 extends GermanLanguageLevel(3)
    case B2 extends GermanLanguageLevel(4)
    case C1 extends GermanLanguageLevel(5)
    case C2 extends GermanLanguageLevel(6)

  final case class GermanLevelResponse(
      germanLevel: Option[GermanLanguageLevel]
  )

  given Decoder[GermanLanguageLevel] = Decoder.decodeString.emapTry { str =>
    Try(GermanLanguageLevel.valueOf(str))
  }

  given Decoder[GermanLevelResponse] = (c: HCursor) =>
    for {
      levelOpt <- c.downField("german_level").as[Option[GermanLanguageLevel]]
    } yield GermanLevelResponse(levelOpt)

  type InputType = String

  def modelName(using aiConfig: AIConfig): String =
    aiConfig.germanLevelDetection.model

  protected def promptGenerator(input: InputType): String =
    s"""
       |## Task: Extract German Level JSON
       |
       |You are a machine that ONLY outputs JSON.
       |Your task is to read the Input Text and find if it **explicitly** states a requirement for **German language skills** (speaking, writing, understanding).
       |
       |## Input Text:
       |```text
       |$input
       |```
       |
       |## Rules:
       |1.  **Search ONLY for explicit phrases** like: "German language", "Deutschkenntnisse", "fluent in German", "German required", "German level", "German speaking/writing".
       |2.  **IGNORE CONTEXT:** Ignore location (Germany), company origin (German company), market/client knowledge (German market). Ignore the fact the input text itself might be in German. These are NOT language skill requirements.
       |3.  **If NO explicit phrase from Rule 1 is found:** Your output MUST be EXACTLY: `{"german_level": null}`
       |4.  **If an explicit phrase IS found:** Determine the CEFR level (C2, C1, B2, B1, A2, A1) based on keywords (Native=C2, Fluent=C1, Advanced/Very Good=B2, Good=B1, Basic=A2). If a requirement exists but the level is vague (e.g., "German required"), use B1. Your output MUST be EXACTLY: `{"german_level": "LEVEL"}` (e.g., `{"german_level": "B1"}`)
       |
       |## CRITICAL OUTPUT INSTRUCTIONS:
       |*   **ONLY OUTPUT THE JSON.**
       |*   **NO introductory text.** (Like "json" or "Here is the JSON:")
       |*   **NO explanations.**
       |*   **NO extra newlines or formatting.**
       |*   The entire output MUST start with `{` and end with `}`.
       |
       |## Example Input resulting in NULL:
       |Input Text: "Wir sind ein deutsches Unternehmen mit Sitz in Berlin."
       |Correct Output: `{"german_level": null}`
       |
       |## Example Input resulting in B1:
       |Input Text: "Requires good knowledge of German."
       |Correct Output: `{"german_level": "B1"}`
       |
       |## Your Output (JSON only):
       |""".stripMargin

}
