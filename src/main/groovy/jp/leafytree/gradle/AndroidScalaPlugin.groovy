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

import com.android.build.gradle.LibraryPlugin
import com.android.build.gradle.BasePlugin
import com.android.build.gradle.api.AndroidSourceDirectorySet
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.internal.scope.VariantScopeImpl
import com.android.build.gradle.internal.variant.BaseVariantData
import com.android.utils.StringHelper
import org.apache.commons.io.FileUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.file.FileCollection
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.internal.tasks.DefaultScalaSourceSet
import org.gradle.api.internal.tasks.DefaultTaskContainer
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.scala.ScalaCompile
import org.gradle.util.ConfigureUtil


//import com.google.common.annotations.VisibleForTesting

import javax.inject.Inject

/**
 * AndroidScalaPlugin adds scala language support to official gradle android plugin.
 */
public class AndroidScalaPlugin implements Plugin<Project> {

	//@VisibleForTesting
	final Map<String, SourceDirectorySet> sourceDirectorySetMap = new HashMap<>()
	private Project project
	private BasePlugin androidPlugin
	private Object androidExtension
	private boolean isLibrary
	private File workDir
	private final AndroidScalaPluginExtension extension = new AndroidScalaPluginExtension()

	private final ObjectFactory objectFactory
	//private final FileResolver fileResolver
	/**
	 * Creates a new AndroidScalaPlugin with given file resolver.
	 *
	 * @param fileResolver the FileResolver
	 */
	@Inject
	public AndroidScalaPlugin(ObjectFactory objectFactory) {
		this.objectFactory = objectFactory
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
//		updateAndroidSourceSetsExtension() If it updates the source sets this early, it doesn't find the unit tests
		androidExtension.buildTypes.whenObjectAdded { updateAndroidSourceSetsExtension() }
		androidExtension.productFlavors.whenObjectAdded { updateAndroidSourceSetsExtension() }
		androidExtension.signingConfigs.whenObjectAdded { updateAndroidSourceSetsExtension() }

		project.afterEvaluate {
			//    updateAndroidSourceSetsExtension()
			//   androidExtension.sourceSets.each { v ->v.java.srcDirs(v.scala.srcDirs) }

			def allVariants = androidExtension.unitTestVariants + androidExtension.testVariants + (isLibrary ? androidExtension.libraryVariants : androidExtension.applicationVariants)
			allVariants.each { variant ->
				//System.out.println(variant.className)
				addAndroidScalaCompileTask(project, variant)
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
	File getVariantWorkDir(BaseVariant variant) {
		new File([workDir, "variant", variant.getDirName()].join(File.separator))
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

			//   def dirSetFactory = new DefaultSourceDirectorySetFactory(fileResolver, new DefaultDirectoryFileTreeFactory())
			// sourceSet.convention.plugins.scala = new DefaultScalaSourceSet(sourceSet.name + "_AndroidScalaPlugin", dirSetFactory)
			sourceSet.convention.plugins.scala = new DefaultScalaSourceSet(sourceSet.name + "_AndroidScalaPlugin", objectFactory)
			SourceDirectorySet scala = sourceSet.scala

			//scala.filter.include(include);
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
	void addAndroidScalaCompileTask(Project project, BaseVariant variant) {
		//com.android.build.gradle.internal.api.TestVariantImpl_Decorated v= variant
		JavaCompile javaCompileTask = variant.javaCompileProvider.get()
		String variantName = variant.name

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
		//	project.dependencies.add(zincConfigurationName,  "org.scala-sbt:zinc_2.12:1.3.1")
			project.dependencies.add(zincConfigurationName, "com.typesafe.zinc:zinc:0.3.15")
		}

//		def zinBridgeName = zincConfigurationName+"Bridge2"
//		def zinBridge = project.configurations.findByName(zinBridgeName)
//		if (!zinBridge) {
//			zinBridge = project.configurations.create(zinBridgeName)
//
////			def i1= scalaVersion.indexOf('.')
////			def i2= scalaVersion.indexOf('.',i1)
////
////			def sv = scalaVersion
////			if (i1>=0 && i2>=0)
////				sv=scalaVersion.substring(0,i2)
//
//			project.dependencies.add(zinBridgeName,  "org.scala-sbt:compiler-bridge_2.12:1.3.1")
//		}

		def compilerConfigurationName = "androidScalaPluginScalaCompilerFor" + javaCompileTask.name
		def compilerConfiguration = project.configurations.findByName(compilerConfigurationName)
		if (!compilerConfiguration) {
			compilerConfiguration = project.configurations.create(compilerConfigurationName)
			project.dependencies.add(compilerConfigurationName, "org.scala-lang:scala-compiler:$scalaVersion")
		}

		def variantWorkDir = getVariantWorkDir(variant)
		ScalaCompile scalaCompileTask = (ScalaCompile) project.tasks.create("compile${variantName.capitalize()}Scala", ScalaCompile)


		List<Object> javaSrcDirs = AndroidGradleWrapper.getJavaSources(variant.variantData).collect {
			if (it instanceof AndroidSourceDirectorySet)
				it.getSrcDirs()
			else
				it
		}.flatten()

		javaSrcDirs.add(GenerationRSource.getOutDir(variant))

		scalaCompileTask.setSource(javaSrcDirs)

		scalaCompileTask.destinationDir = javaCompileTask.destinationDir
		scalaCompileTask.sourceCompatibility = javaCompileTask.sourceCompatibility
		scalaCompileTask.targetCompatibility = javaCompileTask.targetCompatibility
		scalaCompileTask.scalaCompileOptions.setEncoding(javaCompileTask.options.encoding)


		scalaCompileTask.classpath = javaCompileTask.classpath.filter { f -> f.name !="R.jar"} +
				project.files(project.extensions.getByName("android").bootClasspath) +
				project.fileTree(dir: GenerationRSource.getOutDir(variant))
		scalaCompileTask.scalaClasspath = compilerConfiguration.asFileTree
		scalaCompileTask.zincClasspath = zincConfiguration.asFileTree


		scalaCompileTask.scalaCompileOptions.incrementalOptions.analysisFile.set(new File(variantWorkDir, "analysis.txt"))

		if (extension.addparams) {
			scalaCompileTask.scalaCompileOptions.additionalParameters = [extension.addparams]
		}


		String DevDebug = variantName.capitalize()

//		println(">> Scala sources >>>")
//		javaSrcDirs.forEach{println(it)}
	//	scalaCompileTask.source.files.forEach{ println(it)}
//		println(">> Java scalaClasspath >>>")
//		javaCompileTask.classpath.each{
//			println(it.class.toString()+" : "+it)
//
//		}


//

//

		def tasks = [
				"process" + DevDebug + "Resources",
				"generate" + DevDebug + "ResValues",
				"javaPreCompile"+DevDebug,
				"generateSafeArgs"+DevDebug,
				"generate"+DevDebug+"Proto"
		]
				.collect { project.tasks.findByName(it)}
				.findAll { it!=null}

		scalaCompileTask.dependsOn(tasks)

		javaCompileTask.dependsOn.forEach { jctDep ->
			if (jctDep != scalaCompileTask)
				scalaCompileTask.dependsOn(jctDep)
		}

		javaCompileTask.dependsOn(scalaCompileTask)

//        scalaCompileTask.doFirst {
//
//            println("SCALA")
//            scalaCompileTask.source.each {println(it)}
//            println("JAVA")
//            javaCompileTask.source.each {println(it)}
//        }

		def added = false
		def file = scalaCompileTask.destinationDir

		javaCompileTask.doFirst {
			if (!javaCompileTask.classpath.contains(file)) {
				javaCompileTask.classpath += project.files(file)
				added = true
			}
		}
		javaCompileTask.doLast {
			if (added) {
				javaCompileTask.classpath -= project.files(file)
			}
		}
		scalaCompileTask.doFirst {
			GenerationRSource.generateR(variant)
		}

        scalaCompileTask.doLast {
			GenerationRSource.removeR(variant,scalaCompileTask.destinationDir)
        }

//        javaCompileTask.doFirst {
//
//            println("JAVA WITH JAVAC")
//            javaCompileTask.source.each {println(it)}
//            println(javaCompileTask.toolChain.name)
//            println(javaCompileTask.toolChain.displayName)
//            println(javaCompileTask.toolChain.version)
//
//        }

//        javaCompileTask.doLast {
//            println(">>> java complete")

//        }

	}
}
