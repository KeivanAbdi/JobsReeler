package com.keivanabdi.jobsreeler.models.config

final case class AIConfig(
    gemini              : GeminiConfig,
    ollama              : OllamaConfig,
    languageDetection   : LanguageDetectionConfig,
    germanLevelDetection: GermanLevelDetectionConfig
)
