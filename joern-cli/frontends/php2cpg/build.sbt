import scala.sys.process._
import scala.util.Properties.isWin
import better.files.File

name := "php2cpg"

val phpParserVersion       = "4.15.7"
val upstreamParserBinName  = "php-parser.phar"
val versionedParserBinName = s"php-parser-$phpParserVersion.phar"
val phpParserDlUrl =
  s"https://github.com/joernio/PHP-Parser/releases/download/v$phpParserVersion/$upstreamParserBinName"

dependsOn(Projects.dataflowengineoss % "compile->compile;test->test", Projects.x2cpg % "compile->compile;test->test")

libraryDependencies ++= Seq(
  "com.lihaoyi"   %% "upickle"           % Versions.upickle,
  "com.lihaoyi"   %% "ujson"             % Versions.upickle,
  "io.shiftleft"  %% "codepropertygraph" % Versions.cpg,
  "org.scalatest" %% "scalatest"         % Versions.scalatest % Test,
  "io.circe"      %% "circe-core"        % Versions.circe
)

lazy val phpParseInstallTask = taskKey[Unit]("Install PHP-Parse using PHP Composer")
phpParseInstallTask := {
  val phpBinDir = baseDirectory.value / "bin" / "php-parser"
  DownloadHelper.ensureIsAvailable(phpParserDlUrl, phpBinDir / versionedParserBinName)
  File((phpBinDir / "php-parser.php").getPath)
    .createFileIfNotExists()
    .overwrite(s"<?php\nrequire('$versionedParserBinName');?>")

  val distDir = (Universal / stagingDirectory).value / "bin" / "php-parser"
  distDir.mkdirs()
  IO.copyDirectory(phpBinDir, distDir)
}

Compile / compile := ((Compile / compile) dependsOn phpParseInstallTask).value

enablePlugins(JavaAppPackaging, LauncherJarPlugin)
Global / onChangedBuildSource := ReloadOnSourceChanges
