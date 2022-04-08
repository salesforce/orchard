val slickVersion = "3.3.3"
val akkaVersion = "2.6.19"
// make sure this is the same as the playWS's dependency
val playJsonVersion = "2.9.2"
val awsVersion = "2.17.+"

val awsEc2            = "software.amazon.awssdk"     % "ec2"                      % awsVersion
val awsEmr            = "software.amazon.awssdk"     % "emr"                      % awsVersion
val awsSsm            = "software.amazon.awssdk"     % "ssm"                      % awsVersion
val slick             = "com.typesafe.slick"        %% "slick"                    % slickVersion
val slickHikaricp     = "com.typesafe.slick"        %% "slick-hikaricp"           % slickVersion
val postgresql        = "org.postgresql"             % "postgresql"               % "42.3.3"
val playJson          = "com.typesafe.play"         %% "play-json"                % playJsonVersion
val akkaActor         = "com.typesafe.akka"         %% "akka-actor-typed"         % akkaVersion
val akkaTestkit       = "com.typesafe.akka"         %% "akka-actor-testkit-typed" % akkaVersion % Test
val scalaTestArtifact = "org.scalatest"             %% "scalatest"                % "3.2.11" % Test
val scalaPlusPlay     = "org.scalatestplus.play"    %% "scalatestplus-play"       % "5.1.0" % Test
val logback           = "ch.qos.logback"             % "logback-classic"          % "1.2.11" % Test

lazy val commonSettings = Seq(
  scalacOptions ++= Seq("-deprecation", "-feature", "-Xlint"), // , "-Xfatal-warnings"),
  scalaVersion := "2.13.8",
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
  aggregate(orchardCore, orchardWS, orchardProviderAWS)

lazy val orchardCore = (project in file("orchard-core")).
  settings(commonSettings: _*).
  settings(
    name := "orchard-core",
    libraryDependencies ++= Seq(
      slick,
      slickHikaricp,
      postgresql,
      playJson,
      akkaActor,
      akkaTestkit,
      logback
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
      scalaPlusPlay
    )
  ).
  dependsOn(orchardCore, orchardProviderAWS)

lazy val orchardProviderAWS = (project in file("orchard-provider-aws")).
  settings(commonSettings: _*).
  settings(
    name := "orchard-provider-aws",
    libraryDependencies ++= Seq(
      awsEc2,
      awsEmr,
      awsSsm
    )
  ).
  dependsOn(orchardCore)
