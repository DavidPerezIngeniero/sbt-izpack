/*
  ---------------------------------------------------------------------------
  This software is released under a BSD license, adapted from
  http://opensource.org/licenses/bsd-license.php

  Copyright (c) 2010-2011, Brian M. Clapper
  All rights reserved.

  Redistribution and use in source and binary forms, with or without
  modification, are permitted provided that the following conditions are
  met:

  * Redistributions of source code must retain the above copyright notice,
    this list of conditions and the following disclaimer.

  * Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.

  * Neither the names "clapper.org", "sbt-izpack", nor the names of any
    contributors may be used to endorse or promote products derived from
    this software without specific prior written permission.

  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
  IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
  THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
  PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
  CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
  EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
  PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
  PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
  LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
  NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
  ---------------------------------------------------------------------------
*/

package org.clapper.sbt.izpack

import sbt._
import Keys._
import Defaults._
import Project.Initialize
import com.izforge.izpack.compiler.CompilerConfig
import scala.io.Source
import java.io.File
import scala.collection.mutable.{Map => MutableMap}
import grizzled.file.{util => FileUtil}

case class Metadata(installSourceDir: RichFile,
                    baseDirectory: RichFile,
                    scalaVersion: String,
                    private[sbt] val updateReport: UpdateReport)

/**
 * Plugin for SBT (Simple Build Tool) to configure and build an IzPack
 * installer.
 */
object IzPack extends Plugin {
  // -----------------------------------------------------------------
  // Plugin Settings and Tasks
  // -----------------------------------------------------------------

  val IzPack = config("izpack")
  //val izPackConfig = SettingKey[IzPackConfig]("izpack-config")
  val izConfigFile = SettingKey[File]("config-file")
  val izInstallerJar = SettingKey[RichFile]("installer-jar")
  val izInstallSourceDir = SettingKey[File](
    "install-source-dir",
    "Directory containing auxiliary installer source files."
  )
  val izInstallXML = SettingKey[File]("install-xml",
                                      "Path to the generated XML file.")
  val izVariables = SettingKey[Seq[Tuple2[String, String]]](
    "variables", "Additional variables for substitution in the config"
  )
  val izTempDirectory = SettingKey[File](
    "temp-dir", "Where to generate temporary installer files."
  )
  val izLogLevel = SettingKey[Level.Value](
    "log-level", "Log level within sbt-izpack"
  )

  val izCreateXML = TaskKey[RichFile]("create-xml", "Create IzPack XML")
  val izCreateInstaller = TaskKey[Unit]("create-installer",
                                        "Create IzPack installer")

  val izClean = TaskKey[Unit]("clean", "Remove target files.")
  val izPredefinedVariables = TaskKey[Map[String, String]](
    "predefined-variables", "Predefined sbt-izpack variables"
  )

  val izCaptureSettings1 = TaskKey[Map[String,String]](
    "-capture-settings-1",
    "Don't mess with this. Seriously. If you do, you'll break the plugin."
  )

  val izCaptureSettings2 = TaskKey[Map[String,String]](
    "-capture-settings-2",
    "Don't mess with this. Seriously. If you do, you'll break the plugin."
  )

  val izPackSettings: Seq[sbt.Project.Setting[_]] = inConfig(IzPack)(Seq(

    izInstallerJar <<= baseDirectory(_ / "target" / "installer.jar"),
    izInstallSourceDir <<= baseDirectory(_ / "src" / "izpack"),
    izInstallXML <<= baseDirectory(_ / "target" / "izpack.xml"),
    izConfigFile <<= izInstallSourceDir(_ / "izpack.yml"),
    izTempDirectory <<= baseDirectory(_ / "target" / "installtmp"),
    izVariables := Nil,
    izLogLevel := Level.Info,

    izPredefinedVariables <<= predefinedVariablesTask,
    izCaptureSettings1 <<= captureSettingsTask1,
    izCaptureSettings2 <<= captureSettingsTask2,
    izCreateXML <<= createXMLTask,
    izCreateInstaller <<= createInstallerTask,
    izClean <<= cleanTask
  )) ++
  inConfig(Compile)(Seq(
    // Hook our clean into the global one.
    clean in Global <<= (izClean in IzPack).identity,

    (izCreateXML in IzPack) <<= (izCreateXML in IzPack).dependsOn(
      packageBin in Compile
    )
  ))

  // -----------------------------------------------------------------
  // Methods
  // -----------------------------------------------------------------

  private def allDependencies(updateReport: UpdateReport) =
    updateReport.allFiles.map(_.absolutePath).mkString(", ")

  private def predefinedVariablesTask = {
    (izCaptureSettings1) map { m => m }
  }

  private def captureSettingsTask1 = {
    (update, libraryDependencies, target, (classDirectory in Compile),
     name, normalizedName, version, scalaVersion, izCaptureSettings2) map {

      (updateReport, libraryDependencies, target, classDirectory, name,
       normalizedName, version, scalaVersion, settingsMap) =>

      val classesParent = classDirectory.getParentFile.getAbsolutePath
      val jarName = "%s_%s-%s.jar" format (normalizedName,
                                           scalaVersion,
                                           version)
      val appJar = FileUtil.joinPath(classesParent, jarName)
      val allDeps: Seq[(String, ModuleID, Artifact, File)] = updateReport.toSeq
      val allDepFiles = allDeps.map(tuple => tuple._1)

      // Using allDeps, map the library dependencies to their resolved file
      // names.

      val filteredLibDeps = allDeps.filter {tuple =>
        val (s, module, artifact, file) = tuple
        val matched = libraryDependencies.filter { l =>

          // Sometimes, one starts with a prefix of the other (e.g.,
          // libfoo vs. libfoo_2.8.1)
          (module.name.startsWith(l.name) || l.name.startsWith(module.name)) &&
          (l.organization == module.organization)
        }

        matched.length > 0
      }

      // filteredLibDeps is a tuple: (s, module, artifact, file)
      val libDepFiles = filteredLibDeps.map {_._4}.distinct

      settingsMap ++ Seq(
        "appName"             -> name,
        "appVersion"          -> version,
        "normalizedAppName"   -> normalizedName,
        "scalaVersion"        -> scalaVersion,
        "target"              -> target.absolutePath,
        "appJar"              -> appJar,
        "classDirectory"      -> classDirectory.absolutePath,
        "allDependencies"     -> updateReport.allFiles.map{_.absolutePath}.
                                 mkString(", "),
        "libraryDependencies" -> libDepFiles.mkString(", ")
      )
    }
  }

  private def captureSettingsTask2 = {
    (baseDirectory, izInstallSourceDir) map {(base, installSourceDir) =>

      Map.empty[String,String] ++ Seq(
        "baseDirectory"       -> base.absolutePath,
        "installSourceDir"    -> installSourceDir.absolutePath
      )
    }
  }

  private def cleanTask: Initialize[Task[Unit]] = {
    import grizzled.file.GrizzledFile._

    (izInstallXML, izTempDirectory, streams) map {
      (installXML, tempDirectory, streams) =>

      if (installXML.exists) {
        streams.log.debug("Deleting \"%s\"" format installXML)
        installXML.delete
      }

      if (tempDirectory.exists) {
        streams.log.debug("Deleting \"%s\"" format tempDirectory)
        tempDirectory.deleteRecursively
      }
    }
  }

  private def createXMLTask = {
    (izConfigFile, izInstallXML, izVariables, izPredefinedVariables,
     izTempDirectory, izLogLevel, streams) map {

      (configFile, installXML, variables, predefinedVariables,
       tempdir, logLevel, streams) =>

      createXML(configFile, variables, predefinedVariables, installXML,
                tempdir, logLevel, streams.log)
    }
  }

  private def createInstallerTask = {
    (izCreateXML, izInstallerJar, streams) map {(xml, outputJar, streams) =>

      makeInstaller(xml, outputJar, streams.log)
      ()
    }
  }

  private def createXML(configFile: File,
                        variables: Seq[Tuple2[String, String]],
                        predefinedVariables: Map[String, String],
                        installXML: RichFile,
                        tempDirectory: File,
                        logLevel: Level.Value,
                        log: Logger): RichFile = {
    val allVariables = predefinedVariables ++ variables
    val sbtData = new SBTData(allVariables, tempDirectory)
    val parser = new IzPackYamlConfigParser(sbtData, logLevel, log)
    val izConfig = parser.parse(Source.fromFile(configFile))

    // Create the XML.

    val path = installXML.absolutePath
    log.info("Generating IzPack XML \"%s\"" format path)
    izConfig.generateXML(installXML.asFile, log)
    log.info("Created " + path)
    installXML
  }

  /**
   * Build the actual installer jar.
   *
   * @param izPackXML  the IzPack installer XML configuration
   * @param outputJar  where to store the installer jar file
   */
  private def makeInstaller(izPackXML: RichFile,
                            outputJar: RichFile,
                            log: Logger) = {
    IO.withTemporaryDirectory {baseDir =>

      log.info("Generating IzPack installer")
      val compilerConfig = new CompilerConfig(izPackXML.absolutePath,
                                              baseDir.getPath, // basedir
                                              CompilerConfig.STANDARD,
                                              outputJar.absolutePath)
      log.info("Created installer in " + outputJar.absolutePath)
      compilerConfig.executeCompiler
    }
  }
}