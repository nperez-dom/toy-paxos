
name := "zio-paxos"

version := "0.1"

scalaVersion := "2.13.4"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio" % "1.0.3",
  "dev.zio" %% "zio-streams" % "1.0.3",
  "dev.zio" %% "zio-test" % "1.0.3" % "test",
  "dev.zio" %% "zio-test-sbt" % "1.0.3" % "test"
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
