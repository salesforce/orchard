val slickVersion = "3.5.2"
// make sure this is the same as the playWS's dependency
val pekkoVersion = "1.0.3"
val playJsonVersion = "3.0.4"
val jacksonVersion = "2.18.0"
val awsVersion = "2.34.+"
val stubbornVersion = "3.1.0"
val prometheusVersion = "0.16.0"

val awsEc2            = "software.amazon.awssdk"   % "ec2"                        % awsVersion
val awsEmr            = "software.amazon.awssdk"   % "emr"                        % awsVersion
val awsSsm            = "software.amazon.awssdk"   % "ssm"                        % awsVersion
val awsSts            = "software.amazon.awssdk"   % "sts"                        % awsVersion
val awsSns            = "software.amazon.awssdk"   % "sns"                        % awsVersion
val slick             = "com.typesafe.slick"      %% "slick"                      % slickVersion
val slickHikaricp     = "com.typesafe.slick"      %% "slick-hikaricp"             % slickVersion
val postgresql        = "org.postgresql"           % "postgresql"                 % "42.7.7"
val playJson          = "org.playframework"       %% "play-json"                  % playJsonVersion
val pekkoActor        = "org.apache.pekko"        %% "pekko-actor-typed"          % pekkoVersion
val pekkoTestkit      = "org.apache.pekko"        %% "pekko-actor-testkit-typed"  % pekkoVersion % Test

val scalaTestArtifact = "org.scalatest"             %% "scalatest"                % "3.2.19" % Test
val scalaPlusPlay     = "org.scalatestplus.play"    %% "scalatestplus-play"       % "7.0.1" % Test
val logback           = "ch.qos.logback"             % "logback-classic"          % "1.5.19"
val stubbornArtifact  = "com.krux"                  %% "stubborn"                 % stubbornVersion
val prometheusClient  = "io.prometheus"              % "simpleclient"             % prometheusVersion
val prometheusCommon  = "io.prometheus"              % "simpleclient_common"      % prometheusVersion
val prometheusHotSpot = "io.prometheus"              % "simpleclient_hotspot"     % prometheusVersion


lazy val commonSettings = Seq(
  scalacOptions ++= Seq("-deprecation", "-feature", "-Xlint"), // , "-Xfatal-warnings"),
  scalaVersion := "2.13.15",
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
      pekkoActor,
      pekkoTestkit,
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
      // the transitive jackson dependencies from play framework on has security vulnerabilities
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVersion,
      "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % jacksonVersion,
      "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion,
      "com.fasterxml.jackson.core" % "jackson-core" % jacksonVersion,
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % jacksonVersion,
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % jacksonVersion,
      "com.fasterxml.jackson.module" % "jackson-module-parameter-names" % jacksonVersion
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
