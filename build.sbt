scalaVersion := "3.7.1"

lazy val root = (project in file("."))
  .settings(
    name         := "linked-source",
    organization := "com.keivanabdi",
    version      := "0.1.0-SNAPSHOT"
  )

// Versions
val pekkoVersion = "1.1.3"
val circeVersion = "0.14.10"

// Dependencies
libraryDependencies ++= Seq(
  "org.scala-lang.modules"        %% "scala-parser-combinators"  % "2.4.0",
  "com.microsoft.playwright"       % "playwright"                % "1.51.0",
  "org.jsoup"                      % "jsoup"                     % "1.18.3",
  "io.github.ollama4j"             % "ollama4j"                  % "1.0.100",
  "com.keivanabdi"                %% "datareeler"                % "0.1.0",
  "ch.qos.logback"                 % "logback-classic"           % "1.5.18",
  "com.softwaremill.sttp.client4" %% "pekko-http-backend"        % "4.0.3",
  "com.github.pureconfig"         %% "pureconfig-core"           % "0.17.9",
  "com.github.pureconfig"         %% "pureconfig-generic-scala3" % "0.17.9",
  "org.apache.pekko"              %% "pekko-connectors-sse"      % "1.1.0",
  "org.apache.pekko"              %% "pekko-testkit"             % pekkoVersion % Test,
  "org.scalacheck"                %% "scalacheck"                % "1.18.1"     % Test,
  "org.scalatest"                 %% "scalatest"                 % "3.2.19"     % Test
)

// Test Frameworks
testFrameworks += new TestFramework("org.scalatest.tools.Framework")

// Forking and Input
fork               := true
run / connectInput := true

// Scala Compiler Options
scalacOptions ++= Seq(
  "-Wunused:all",
  "-Xmax-inlines:64" // Circe related
)

// Java Compiler Options
javacOptions ++= Seq(
  "--add-opens=java.base/sun.misc=ALL-UNNAMED"
)
