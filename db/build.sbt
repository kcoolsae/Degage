name := "Degage - db layer"

normalizedName := "db"

version := "1.0-SNAPSHOT"

organization := "be.ugent.degage"

crossPaths := false

libraryDependencies += "mysql" % "mysql-connector-java" % "5.1.29"

libraryDependencies += "org.apache.commons" % "commons-lang3" % "3.1"

libraryDependencies += "joda-time" % "joda-time" % "2.3"

autoScalaLibrary := false

parallelExecution in Test := false

