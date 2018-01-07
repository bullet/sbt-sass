package org.madoushi.sbt.sass

import com.typesafe.sbt.web.{incremental, SbtWeb}
import com.typesafe.sbt.web.incremental._
import com.typesafe.sbt.web.incremental.OpSuccess
import sbt.Keys._
import sbt._

object Import {

  val sass = TaskKey[Seq[File]]("sass", "Generate css files from scss and sass.")

  val sassExecutable = SettingKey[Seq[String]]("sassExecutable", "The full path to the sass executable can be provided here if neccessary.")

  val sassOptions = SettingKey[Seq[String]]("sassOptions", "Additional options that are passed to the sass executable.")

  val sassGenerateMinifiedOutput = SettingKey[Boolean]("sassGenerateMinifiedOutput", "Generate minified output for every input file in addition to the unminified output.")
}

object SbtSass extends AutoPlugin {

  override def requires: Plugins = SbtWeb

  override def trigger: PluginTrigger = AllRequirements

  val autoImport = Import

  import SbtWeb.autoImport._
  import WebKeys._
  import autoImport._

  val baseSbtSassSettings = Seq(
    excludeFilter in sass := HiddenFileFilter || "_*",
    includeFilter in sass := "*.sass" || "*.scss",

    managedResourceDirectories += (resourceManaged in sass in Assets).value,
    resourceManaged in sass in Assets := webTarget.value / "sass" / "main",
    resourceGenerators in Assets += sass in Assets,

    sass in Assets := Def.task {
      val sourceDir = (sourceDirectory in Assets).value
      val targetDir = (resourceManaged in sass in Assets).value
      val sources = (sourceDir ** ((includeFilter in sass in Assets).value -- (excludeFilter in sass in Assets).value)).get
      val generateMinifiedOutput = sassGenerateMinifiedOutput.value

      val results = incremental.syncIncremental((streams in Assets).value.cacheDirectory / "run", sources) {
        modifiedSources: Seq[File] =>
          if (modifiedSources.nonEmpty)
            streams.value.log.info(s"Sass compiling on ${modifiedSources.size} source(s)")

          val compilationResults = modifiedSources map { source =>
            val sourceName = source.getPath.drop(sourceDir.getPath.length).reverse.dropWhile(_ != '.').reverse
            def sourceWithExtn(extn: String): File = targetDir / (sourceName + extn)
            val targetFileCss = sourceWithExtn("css")
            val targetFileCssMap = sourceWithExtn("css.map")
            val targetFileCssMin = sourceWithExtn("min.css")
            val targetFileCssMinMap = sourceWithExtn("min.css.map")

            // prepares folders for results of sass compiler
            targetFileCss.getParentFile.mkdirs()

            // function compiles, creates files and returns imported css-dependencies
            val dependencies = SassCompiler.compile(sassExecutable.value, source, targetFileCss,
              if (generateMinifiedOutput) Some(targetFileCssMin) else None,
              sassOptions.value)

            // converting dependencies path from ../../../../file.sass to /normal/absolute/path/to/file.sass
            val readFiles = dependencies.map { (path) =>
              val formattedPath = baseDirectory.value +
                java.io.File.separator +
                file(path.replaceAll( """(\.\.\/|\.\.\\)""", "")).toPath.normalize().toString
              file(formattedPath)
            }.toSet + source

            val cssFilesWritten = Set(targetFileCss) ++ (if (generateMinifiedOutput) Set(targetFileCssMin) else Set.empty)
            val mapFilesWritten = Set(targetFileCssMap) ++ (if (generateMinifiedOutput) Set(targetFileCssMinMap) else Set.empty)

            (cssFilesWritten,
              source,
              OpSuccess(readFiles, cssFilesWritten ++ mapFilesWritten))
          }
          val createdFiles = compilationResults.flatMap(_._1).toSet
          val cachedForIncrementalCompilation = compilationResults.foldLeft(Map.empty[File, OpResult]) { (acc, sourceAndResultFiles) =>
            acc ++ Map((sourceAndResultFiles._2, sourceAndResultFiles._3))
          }
          (cachedForIncrementalCompilation, createdFiles)
      }

      if(results._2.nonEmpty){
        streams.value.log.info(s"Sass compilation results: ${results._2.mkString(", ")}")
      }

      (results._1 ++ results._2).toSeq

    }.dependsOn(WebKeys.webModules in Assets).value,

    sassExecutable in Assets := SassCompiler.command,
    sassOptions in Assets := (webModuleDirectories in Assets).value.getPaths.foldLeft(Seq.empty[String]){ (acc, str) => acc ++ Seq("-I", str) },
    sassGenerateMinifiedOutput in Assets := true
  )

  override def projectSettings: Seq[Setting[_]] = inConfig(Assets)(baseSbtSassSettings)
}
