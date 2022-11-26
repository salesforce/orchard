addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.10.0")

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.8.17")

addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.6.5")

// https://eed3si9n.com/sbt-1.8.0
ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
