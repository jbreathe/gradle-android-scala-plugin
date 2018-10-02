/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jp.leafytree.gradle
//import com.google.common.annotations.VisibleForTesting
import org.apache.commons.io.FileUtils
import org.codehaus.groovy.runtime.InvokerHelper
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileTree
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.internal.AbstractTask
import org.gradle.api.internal.file.collections.DefaultDirectoryFileTreeFactory
import org.gradle.api.internal.file.DefaultSourceDirectorySetFactory
import org.gradle.api.internal.file.FileResolver
import org.gradle.api.internal.tasks.DefaultScalaSourceSet
import org.gradle.api.internal.tasks.DefaultTaskContainer
import org.gradle.api.tasks.StopExecutionException
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.scala.ScalaCompile
import org.gradle.util.ConfigureUtil

import javax.inject.Inject
import java.util.concurrent.atomic.AtomicReference
/**
 * AndroidScalaPlugin adds scala language support to official gradle android plugin.
 */
public class AndroidScalaPlugin implements Plugin<Project> {
    private final FileResolver fileResolver
    //@VisibleForTesting
    final Map<String, SourceDirectorySet> sourceDirectorySetMap = new HashMap<>()
    private Project project
    private Object androidPlugin
    private Object androidExtension
    private boolean isLibrary
    private File workDir
    private final AndroidScalaPluginExtension extension = new AndroidScalaPluginExtension()

    /**
     * Creates a new AndroidScalaPlugin with given file resolver.
     *
     * @param fileResolver the FileResolver
     */
    @Inject
    public AndroidScalaPlugin(FileResolver fileResolver) {
        this.fileResolver = fileResolver
    }

    /**
     * Registers the plugin to current project.
     *
     * @param project currnet project
     * @param androidExtension extension of Android Plugin
     */
    void apply(Project project, Object androidExtension) {
        this.project = project
        if (project.plugins.hasPlugin("android-library")) {
            isLibrary = true
            androidPlugin = project.plugins.findPlugin("android-library")
        } else {
            isLibrary = false
            androidPlugin = project.plugins.findPlugin("android")
        }
        this.androidExtension = androidExtension
        this.workDir = new File(project.buildDir, "android-scala")
        updateAndroidExtension()
        updateAndroidSourceSetsExtension()
        androidExtension.buildTypes.whenObjectAdded { updateAndroidSourceSetsExtension() }
        androidExtension.productFlavors.whenObjectAdded { updateAndroidSourceSetsExtension() }
        androidExtension.signingConfigs.whenObjectAdded { updateAndroidSourceSetsExtension() }

        project.afterEvaluate {
            updateAndroidSourceSetsExtension()
         //   androidExtension.sourceSets.each { v ->v.java.srcDirs(v.scala.srcDirs) }

            def allVariants = androidExtension.testVariants + (isLibrary ? androidExtension.libraryVariants : androidExtension.applicationVariants)
            allVariants.each { variant ->
                //System.out.println(variant.className)
                addAndroidScalaCompileTask(variant)
            }
        }

        project.tasks.findByName("preBuild").doLast {
            FileUtils.forceMkdir(workDir)
        }

        //project.tasks.withType(ScalaCompile) {
            //scalaCompileOptions.useAnt = false
        //}
    }

    /**
     * Registers the plugin to current project.
     *
     * @param project currnet project
     * @param androidExtension extension of Android Plugin
     */
    public void apply(Project project) {
        if (!["com.android.application", 
		"android", 
		"com.android.library", 
		"android-library",
		"com.android.model.application",
		"com.android.model.library"].any { project.plugins.findPlugin(it) }) {
            throw new ProjectConfigurationException("Please apply 'com.android.application' or 'com.android.library' plugin before applying 'android-scala' plugin", null)
        }


        apply(project, project.extensions.getByName("android"))
    }

    /**
     * Returns directory for plugin's private working directory for argument
     *
     * @param variant the Variant
     * @return
     */
    File getVariantWorkDir(Object variant) {
        new File([workDir, "variant", variant.name].join(File.separator))
    }

    /**
     * Returns scala version from scala-library in given classpath.
     *
     * @param classpath the classpath contains scala-library
     * @return scala version
     */
    static String scalaVersionFromClasspath(Collection<File> classpath) {
        def urls = classpath.collect { it.toURI().toURL() }
        def classLoader = new URLClassLoader(urls.toArray(new URL[0]))
        try {
            def propertiesClass
            try {
                propertiesClass = classLoader.loadClass("scala.util.Properties\$")
            } catch (ClassNotFoundException e) {
                return null
            }
            def versionNumber = propertiesClass.MODULE$.scalaProps["maven.version.number"]
            return new String(versionNumber) // Remove reference from ClassLoader
        } finally {
            if (classLoader instanceof Closeable) {
                classLoader.close()
            }
        }
    }

    /**
     * Updates AndroidPlugin's root extension to work with AndroidScalaPlugin.
     */
    void updateAndroidExtension() {
        androidExtension.metaClass.getScala = { extension }
        androidExtension.metaClass.scala = { configureClosure ->
            ConfigureUtil.configure(configureClosure, extension)
            androidExtension
        }
    }

    /**
     * Updates AndroidPlugin's sourceSets extension to work with AndroidScalaPlugin.
     */
    void updateAndroidSourceSetsExtension() {
        androidExtension.sourceSets.each { sourceSet ->
            if (sourceDirectorySetMap.containsKey(sourceSet.name)) {
                return
            }
            def include = "**/*.scala"
            sourceSet.java.filter.include(include);
            def dirSetFactory = new DefaultSourceDirectorySetFactory(fileResolver, new DefaultDirectoryFileTreeFactory())
            sourceSet.convention.plugins.scala = new DefaultScalaSourceSet(sourceSet.name + "_AndroidScalaPlugin", dirSetFactory)
            SourceDirectorySet scala = sourceSet.scala


            scala.filter.include(include);
            def scalaSrcDir = ["src", sourceSet.name, "scala"].join(File.separator)
            scala.srcDir(scalaSrcDir)
            sourceDirectorySetMap[sourceSet.name] = scala
        }
    }

    /**
     * Updates AndroidPlugin's compilation task to support scala.
     *
     * @param task the JavaCompile task
     */
    void addAndroidScalaCompileTask(Object variant) {
        AbstractCompile javaCompileTask = variant.javaCompileProvider.get()


        // com.android.build.gradle.tasks.AndroidJavaCompile
        // To prevent locking classes.jar by JDK6's URLClassLoader
        def libraryClasspath = javaCompileTask.classpath.grep { it.name != "classes.jar" }
        def scalaVersion = scalaVersionFromClasspath(libraryClasspath)
        if (!scalaVersion) {
            return
        }
        project.logger.info("scala-library version=$scalaVersion detected")

        def zincConfigurationName = "androidScalaPluginZincFor" + javaCompileTask.name
        def zincConfiguration = project.configurations.findByName(zincConfigurationName)
        if (!zincConfiguration) {
            zincConfiguration = project.configurations.create(zincConfigurationName)
         //  project.dependencies.add(zincConfigurationName,  "org.scala-sbt:zinc_2.11:1.2.1")
           project.dependencies.add(zincConfigurationName, "com.typesafe.zinc:zinc:0.3.15")
        }


        def compilerConfigurationName = "androidScalaPluginScalaCompilerFor" + javaCompileTask.name
        def compilerConfiguration = project.configurations.findByName(compilerConfigurationName)
        if (!compilerConfiguration) {
            compilerConfiguration = project.configurations.create(compilerConfigurationName)
            project.dependencies.add(compilerConfigurationName, "org.scala-lang:scala-compiler:$scalaVersion")
        }

        def variantWorkDir = getVariantWorkDir(variant)
        ScalaCompile scalaCompileTask = (ScalaCompile)project.tasks.create("compile${variant.name.capitalize()}Scala", ScalaCompile)

        def scalaSources = variant.variantData.variantConfiguration.sortedSourceProviders.inject([]) { acc, val ->
            acc + val.java.sourceFiles
        }

        scalaCompileTask.source = scalaSources
        scalaCompileTask.destinationDir = javaCompileTask.destinationDir
        scalaCompileTask.sourceCompatibility = javaCompileTask.sourceCompatibility
        scalaCompileTask.targetCompatibility = javaCompileTask.targetCompatibility
        scalaCompileTask.scalaCompileOptions.encoding = javaCompileTask.options.encoding
        scalaCompileTask.classpath = javaCompileTask.classpath + project.files(androidPlugin.androidBuilder.getBootClasspath(false))
        scalaCompileTask.scalaClasspath = compilerConfiguration.asFileTree
        scalaCompileTask.zincClasspath = zincConfiguration.asFileTree



        //scalaCompileTask.scalaCompileOptions.incrementalOptions.analysisFile = new File(variantWorkDir, "analysis.txt")

        if (extension.addparams) {
            scalaCompileTask.scalaCompileOptions.additionalParameters = [extension.addparams]
        }

//        def javaCompileOriginalOptionsCompilerArgs = new AtomicReference<List<String>>()
//        def onlyAnnotationProc=  new AtomicReference<Boolean>()
//
//        List<Action<? super Task>> actions=javaCompileTask.actions
//        for (act in actions){
//
//            scalaCompileTask.doFirst(act)
//        }

        javaCompileTask.doFirst {
        //    def tree = [] + new TreeSet(scalaCompileTask.source.collect { it } + javaCompileTask.source.collect { it }) // unique
          //  tree.forEach{scalaCompileTask.source(it)}
          //  scalaCompileTask.source = tree

            scalaCompileTask.execute()
            if (true) { throw new StopExecutionException() }

//            List<String> compilerArgs = javaCompileTask.options.compilerArgs
//            javaCompileOriginalOptionsCompilerArgs.set(compilerArgs)
//
//            boolean b=false
//
//            println(">>>Task>>>")
//            List<Action<? super Task>> actions=javaCompileTask.actions
//            for (act in actions){
//                if (act instanceof AbstractTask.TaskActionWrapper){
//
//                    def act2=(AbstractTask.TaskActionWrapper)act
//
//
//
//                }
//                println act.toString()
//                println act.properties
//                println()
//            }
//            println(">>>Sources>>>")
//            for ( e in  javaCompileTask.source ) {
//                println e
//            }
//            println(">>>Args>>>")
//            for ( e in compilerArgs ) {
//                println e          // Распечатываем все элементы списка someList
//                if (e == "-proc:only" || e.startsWith("-Aandroid.databinding."))
//                    b=true
//            }
//            println("<<<<<<")
//
//            onlyAnnotationProc.set(b)

//            javaCompileTask.options.compilerArgs = compilerArgs + "-proc:only"
           // javaCompileTask.enabled = false
        }


//       scalaCompileTask.doFirst {">>>Scala compile<<<"}
//        jct.actions.add(3,scalaCompileTask)

        javaCompileTask.doLast {

//            if (!onlyAnnotationProc.get().booleanValue()) {
//                scalaCompileTask.source = [] + new TreeSet(scalaCompileTask.source.collect { it } + javaCompileTask.source.collect { it }) // unique
//                scalaCompileTask.execute()
//            }
        }

//        scalaCompileTask.doLast {
//            println("use annotation processor")
//
//        }


        //=======================
//        def dummyDestinationDir = new File(variantWorkDir, "javaCompileDummyDestination") // TODO: More elegant way
//        def dummySourceDir = new File(variantWorkDir, "javaCompileDummySource") // TODO: More elegant way
//
//        def javaCompileOriginalDestinationDir = new AtomicReference<File>()
//        def javaCompileOriginalSource = new AtomicReference<FileCollection>()
//        def javaCompileOriginalOptionsCompilerArgs = new AtomicReference<List<String>>()
//        javaCompileTask.doFirst {
//            // Disable compilation
//            javaCompileOriginalDestinationDir.set(javaCompileTask.destinationDir)
//            javaCompileOriginalSource.set(javaCompileTask.source)
//            javaCompileTask.destinationDir = dummyDestinationDir
//            if (!dummyDestinationDir.exists()) {
//                FileUtils.forceMkdir(dummyDestinationDir)
//            }
//            def dummySourceFile = new File(dummySourceDir, "Dummy.java")
//            if (!dummySourceFile.exists()) {
//                FileUtils.forceMkdir(dummySourceDir)
//                dummySourceFile.withWriter { it.write("class Dummy{}") }
//            }
//
//            javaCompileTask.source = [dummySourceFile]
//            def compilerArgs = javaCompileTask.options.compilerArgs
//            javaCompileOriginalOptionsCompilerArgs.set(compilerArgs)
//            javaCompileTask.options.compilerArgs = compilerArgs +  "-proc:none"
//        }
//
//        javaCompileTask.outputs.upToDateWhen { false }
//
//        javaCompileTask.doLast {
//            FileUtils.deleteDirectory(dummyDestinationDir)
//            javaCompileTask.destinationDir = javaCompileOriginalDestinationDir.get()
//            javaCompileTask.source = javaCompileOriginalSource.get()
//            javaCompileTask.options.compilerArgs = javaCompileOriginalOptionsCompilerArgs.get()
//
//            // R.java is appended lazily
//            scalaCompileTask.source = [] + new TreeSet(scalaCompileTask.source.collect { it } + javaCompileTask.source.collect { it }) // unique
//            def noisyProperties = ["compiler", "includeJavaRuntime", "incremental", "optimize", "useAnt"]
//            InvokerHelper.setProperties(scalaCompileTask.options,
//                javaCompileTask.options.properties.findAll { !noisyProperties.contains(it.key) })
//            noisyProperties.each { property ->
//                // Suppress message from deprecated/experimental property as possible
//                if (!javaCompileTask.options.hasProperty(property) || !scalaCompileTask.options.hasProperty(property)) {
//                    return
//                }
//                if (scalaCompileTask.options[property] != javaCompileTask.options[property]) {
//                    scalaCompileTask.options[property] = javaCompileTask.options[property]
//                }
//            }
//            scalaCompileTask.execute()
//            project.logger.lifecycle(scalaCompileTask.path)
//        }
    }
}
