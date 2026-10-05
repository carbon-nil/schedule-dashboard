val http4sVersion = "0.23.38"
val circeVersion = "0.14.16"

lazy val root = (project in file("."))
    .settings(
        name := "schedule-dashboard",
        scalafmtConfig := file("../.scalafmt.conf"),
        scalafixConfig := Some(file("../.scalafix.conf")),
        scalaVersion := "3.9.0",
        scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all"),
        semanticdbEnabled := true,
        // IO の for 内でタプルを分解するため (withFilter を要求しない irrefutable パターン)
        Test / scalacOptions += "-source:future",
        libraryDependencies ++= Seq(
            "org.typelevel" %% "cats-effect" % "3.7.1",
            "org.http4s" %% "http4s-ember-server" % http4sVersion,
            "org.http4s" %% "http4s-ember-client" % http4sVersion,
            "org.http4s" %% "http4s-dsl" % http4sVersion,
            "org.http4s" %% "http4s-circe" % http4sVersion,
            "io.circe" %% "circe-generic" % circeVersion,
            "io.circe" %% "circe-parser" % circeVersion,
            "org.tpolecat" %% "doobie-core" % "1.0.0-RC12",
            "org.xerial" % "sqlite-jdbc" % "3.53.4.0",
            "ch.qos.logback" % "logback-classic" % "1.6.5" % Runtime,
            "org.typelevel" %% "munit-cats-effect" % "2.2.1" % Test
        ),
        assembly / assemblyJarName := "schedule-dashboard.jar",
        assembly / assemblyMergeStrategy := {
            case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
            case "module-info.class"                                      => MergeStrategy.discard
            case x                                                        => (assembly / assemblyMergeStrategy).value(x)
        }
    )
