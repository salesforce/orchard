val slickVersion = "3.3.3"
val akkaVersion = "2.6.14"
// make sure this is the same as the playWS's dependency
val playJsonVersion = "2.8.1"
val awsVersion = "2.17.+"

val scalaTestArtifact = "org.scalatest" %% "scalatest" % "3.2.9" % Test

lazy val commonSettings = Seq(
  scalacOptions ++= Seq("-deprecation", "-feature", "-Xlint"), // , "-Xfatal-warnings"),
  scalaVersion := "2.13.6",
  libraryDependencies += scalaTestArtifact,
  organization := "com.salesforce.mce",
  headerLicense := Some(HeaderLicense.Custom(
    """|Copyright (c) 2022, salesforce.com, inc.
       |All rights reserved.
       |SPDX-License-Identifier: BSD-3-Clause
       |For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
       |""".stripMargin
  ))
)

lazy val root = (project in file(".")).
  settings(commonSettings: _*).
  settings(
    name := "orchard",
    libraryDependencies ++= Seq(
      // Add your dependencies here
    )
  ).
  aggregate(orchardCore, orchardWS, orchardTR)

lazy val orchardCore = (project in file("orchard-core")).
  settings(commonSettings: _*).
  settings(
    name := "orchard-core",
    libraryDependencies ++= Seq(
      "com.typesafe.slick" %% "slick" % slickVersion,
      "com.typesafe.slick" %% "slick-hikaricp" % slickVersion,
      "org.postgresql" % "postgresql" % "42.2.21",
      "com.typesafe.play" %% "play-json" % playJsonVersion,
      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test
    )
  )

lazy val orchardWS = (project in file("orchard-ws")).
  enablePlugins(PlayScala, BuildInfoPlugin).
  settings(commonSettings: _*).
  settings(
    name := "orchard-ws",
    buildInfoKeys := Seq[BuildInfoKey](name, version),
    buildInfoPackage := "com.salesforce.mce.orchard.ws",
    libraryDependencies ++= Seq(
      guice,
      "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0" % Test
    )
  ).
  dependsOn(orchardCore, orchardProviderAWS)

lazy val orchardProviderAWS = (project in file("orchard-provider-aws")).
  settings(commonSettings: _*).
  settings(
    name := "orchard-provider-aws",
    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "emr" % awsVersion
    )
  ).
  dependsOn(orchardCore)

lazy val orchardTR = (project in file ("orchard-tr")).
  settings(commonSettings: _*).
  settings(
    name := "orchard-tr",
    libraryDependencies ++= Seq(
    )
  )
