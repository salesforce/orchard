val slickVersion = "3.4.1"
// make sure this is the same as the playWS's dependency
val akkaVersion = "2.6.20"
val playJsonVersion = "2.9.4"
val awsVersion = "2.20.+"
val stubbornVersion = "3.1.0"
val prometheusVersion = "0.16.0"

val awsEc2            = "software.amazon.awssdk"   % "ec2"                        % awsVersion
val awsEmr            = "software.amazon.awssdk"   % "emr"                        % awsVersion
val awsSsm            = "software.amazon.awssdk"   % "ssm"                        % awsVersion
val awsSts            = "software.amazon.awssdk"   % "sts"                        % awsVersion
val awsSns            = "software.amazon.awssdk"   % "sns"                        % awsVersion
val slick             = "com.typesafe.slick"      %% "slick"                      % slickVersion
val slickHikaricp     = "com.typesafe.slick"      %% "slick-hikaricp"             % slickVersion
val postgresql        = "org.postgresql"           % "postgresql"                 % "42.5.4"
val playJson          = "com.typesafe.play"       %% "play-json"                  % playJsonVersion
val akkaActor         = "com.typesafe.akka"       %% "akka-actor-typed"           % akkaVersion
val akkaTestkit       = "com.typesafe.akka"         %% "akka-actor-testkit-typed" % akkaVersion % Test

val scalaTestArtifact = "org.scalatest"             %% "scalatest"                % "3.2.15" % Test
val scalaPlusPlay     = "org.scalatestplus.play"    %% "scalatestplus-play"       % "5.1.0" % Test
val logback           = "ch.qos.logback"             % "logback-classic"          % "1.4.5"
val stubbornArtifact  = "com.krux"                  %% "stubborn"                 % stubbornVersion
val prometheusClient  = "io.prometheus"              % "simpleclient"             % prometheusVersion
val prometheusCommon  = "io.prometheus"              % "simpleclient_common"      % prometheusVersion
val prometheusHotSpot = "io.prometheus"              % "simpleclient_hotspot"     % prometheusVersion


lazy val commonSettings = Seq(
  scalacOptions ++= Seq("-deprecation", "-feature", "-Xlint"), // , "-Xfatal-warnings"),
  scalaVersion := "2.13.10",
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
      logback % Test,
      stubbornArtifact
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
      prometheusClient,
      prometheusCommon,
      prometheusHotSpot,
      scalaPlusPlay,
      logback
    ),
    dependencyOverrides ++= Seq(
      // fix https://nvd.nist.gov/vuln/detail/CVE-2022-42003
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.13.4.1",
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.13.4"
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
      awsSsm,
      awsSts,
      awsSns
    )
  ).
  dependsOn(orchardCore)
