package emberjs

import sbt._
import sbt.Keys._
import sbt.Project.Initialize
import scala.collection.JavaConversions._
import java.io.InputStream
import java.nio.charset.Charset

object EmberjsPlugin extends Plugin {
  import EmberjsKeys._

  object EmberjsKeys {
    val emberjs = TaskKey[Seq[File]]("emberjs", "Create ember.js application")
    val coffeeDirectory = SettingKey[File]("coffeeDir", "Directory used by coffeescript plugin")
    val librariesDirectory = SettingKey[File]("libraries", "Directory containing the ember.js and other provided javascript libraries")
    val templatesDirectory = SettingKey[File]("templates", "Directory containing the handlebars templates")
    val minify = SettingKey[Boolean]("minify", "Minify javascript sources after aggregation")
    val charset = SettingKey[Charset]("charset", "Sets the character encoding used in file IO. Defaults to utf-8")
    val name = SettingKey[String]("name", "Sets the application name for this application. Defaults to project name")
  }

  private def aggregateJavascript(
      name: String, 
      libs: File,
      sources: File,
      templates: File,
      managed: File,
      target: File,
      minify: Boolean,
      charset: Charset,
      log: Logger) = {

    // Create the aggregation target file
    target.mkdirs()
    val js = target / "application.js"

    // Include "spade" as first dependency. It is delivered in the plugins resource folder.
    val stream = getClass().getResourceAsStream("/spade-1.0.2.js")
    val spade = IO.readStream(stream, charset)
    IO.write(js, spade, charset)
    stream.close

    // Include libraries
    val libFiles = (libs ** ("*.js")).get 
    libFiles foreach { f =>
      IO.append(js, createJavascriptArtifact("lib/", f, libs, charset))
      log.debug("[Emberjs][Library] Appended " + f)
    }
    log.info("[Emberjs] Appended %d libraries to the javascript output".format(libFiles.length))

    // Include sources
    val sourceFiles = (sources ** ("*.js")).get
    sourceFiles foreach { f =>
      IO.append(js, createJavascriptArtifact(name + "/", f, sources, charset))
      log.debug("[Emberjs][Source] Appended " + f)
    }
    log.info("[Emberjs] Appended %d source files to the javascript output".format(sourceFiles.length))

    // Include managed sources
    val managedFiles = (managed ** ("*.js")).get
    managedFiles foreach { f =>
      IO.append(js, createJavascriptArtifact(name + "/", f, managed, charset))
      log.debug("[Emberjs][Managed Source] Appended " + f)
    }
    log.info("[Emberjs] Appended %d managed source files to the javascript output".format(managedFiles.length))

    // Include handlebars templates
    val templateFiles = (templates ** ("*.handlebars")).get 
     templateFiles foreach { f =>
      IO.append(js, createHandlebarsArtifact(name + "/~template/", f, templates, charset))
      log.debug("[Emberjs][Template] Appended " + f)
    }
    log.info("[Emberjs] Appended %d handlebars templates to the javascript output".format(templateFiles.length))

    Seq( js )
  }

  private def spadeWrapper(prefix: String, file: File, dir: File) = {
    val spade = prefix + 
      file.absolutePath.substring(dir.absolutePath.length()+1, file.absolutePath.lastIndexOf("."))
      .replaceFirst("-[0-9]+\\.[0-9]+.*$", "");
    
    """
spade.register("%s", function(require, exports,__module,ARGV,ENV,__filename){
    %s
});
    """.format(spade, "%s")
  }

  private def createJavascriptArtifact(prefix: String, file: File, dir: File, charset: Charset) = {
    spadeWrapper(prefix, file, dir).format(IO.read(file, charset))
  }

  private def createHandlebarsArtifact(prefix: String, file: File, dir: File, charset: Charset) = {
    // TODO Precompile the handlebars templates to improve the performance in production mode
    val template = IO.read(file, charset).replaceAll("\n", "\\\\n").replaceAll("\"", "\\\\\"")
    val content = """return Ember.Handlebars.compile("%s");""".format(template)
    spadeWrapper(prefix, file, dir).format(content)
  }

  private def emberjsAggregationTask = 
    (streams, name in emberjs, librariesDirectory in emberjs, sourceDirectory in emberjs,
      templatesDirectory in emberjs, coffeeDirectory in emberjs, resourceManaged in emberjs, 
      minify in emberjs, charset in emberjs) map {
        (out, name, libDir, sourceDir, templateDir, genSourceDir, targetDir, minify, charset) =>
          aggregateJavascript(name, libDir, sourceDir, templateDir, genSourceDir, targetDir, minify, charset, out.log)
   }

  private def emberjsCleanTask = (streams, resourceManaged in emberjs) map {
    (out, target) =>
      out.log.info("Cleaning aggregated ember.js JavaScript under " + target)
      IO.delete(target)
  }

  private def emberjsSourcesTask = 
    (streams, librariesDirectory in emberjs, sourceDirectory in emberjs, templatesDirectory in emberjs) map {
        (out, libDir, sourceDir, templateDir) => 
            ((libDir +++ sourceDir +++ templateDir) ** ("*.js" || "*.handlebars")).get
  }
  
  def coreEmberjsSettings: Seq[Setting[_]] = (Seq(
    charset in emberjs := IO.utf8,
    minify in emberjs := false,
    unmanagedSources in emberjs <<= emberjsSourcesTask,
    clean in emberjs <<= emberjsCleanTask,
    emberjs <<= emberjsAggregationTask
  ))

  def emberjsSettingsIn(c: Configuration): Seq[Setting[_]] =
    inConfig(c)(coreEmberjsSettings ++ Seq(
      name in emberjs <<= (name in c),
      librariesDirectory in emberjs <<= (sourceDirectory in c) { _ / "emberjs" / "libs" },
      sourceDirectory in emberjs <<= (sourceDirectory in c) { _ / "emberjs" / "js" },
      templatesDirectory in emberjs <<= (sourceDirectory in c) { _ / "emberjs" / "templates" },
      coffeeDirectory in emberjs <<= (crossTarget in c) { _ / "resource_managed" / "main" / "js" },
      resourceManaged in emberjs <<= (resourceManaged in c) { _ / "assets" },
      cleanFiles in emberjs <<= (resourceManaged in emberjs)(_ :: Nil),
      watchSources in emberjs <<= (unmanagedSources in emberjs)
    )) ++ Seq(
      cleanFiles <+= (resourceManaged in emberjs in c),
      watchSources <++= (unmanagedSources in emberjs in c),
      resourceGenerators in c <+= emberjs in c,
      compile in c <<= (compile in c).dependsOn(emberjs in c)
    )

  def emberjsSettings: Seq[Setting[_]] = emberjsSettingsIn(Compile) ++ emberjsSettingsIn(Test)

}
