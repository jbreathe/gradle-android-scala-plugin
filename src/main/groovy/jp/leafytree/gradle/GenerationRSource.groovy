package jp.leafytree.gradle

import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.internal.scope.VariantScopeImpl
import com.android.build.gradle.internal.variant.BaseVariantData
import com.android.utils.FileUtils
import com.android.utils.StringHelper

class GenerationRSource {

    static File getOutDir(BaseVariant variant){

        BaseVariantData variantData = variant.variantData;
        VariantScopeImpl scope = variantData.scope;

        return FileUtils.join(scope.globalScope.getGeneratedDir(),
                StringHelper.toStrings(
                        "source",
                       // "buildConfig",
                        "scala_compiler",
                        variant.getDirName())
        )
    }

    static def getProjectPackageName(BaseVariant variant) {
        def packageName = [variant.applicationId, variant.buildType.applicationIdSuffix].findAll().join()
        return packageName
    }

    static void generateR(BaseVariant variant){

        String variantName = variant.name
        String DevDebug = variantName.capitalize()

      //  println(">> Variant "+ DevDebug)

        BaseVariantData variantData = variant.variantData;
        VariantScopeImpl scope = variantData.scope;

        def srcFile =  FileUtils.join(scope.globalScope.getIntermediatesDir(),
                StringHelper.toStrings(
                "symbol_list_with_package_name",
                        variant.getDirName(),
                "package-aware-r.txt"))

        def outDir = getOutDir(variant)

        //	println(">> intermediate "+ scope.getIntermediatesDir())
//        println(">> dir "+ variant.getDirName())
//        println(">> src "+ srcFile)
//        println(">> out "+ outDir)

        outDir.mkdirs()

        srcFile.withReader('UTF-8') { reader ->

            def pack = reader.readLine()
//            println(">> pack  " + pack)
            def packDir = pack.replace('.', File.separator)
//            println(">> packdir  " + packDir)

            def resFile = new File(outDir, packDir)
//            println(">> "+resFile)
            resFile.mkdirs()
            def rFile = new File(resFile, "R.java")

//            LinkedHashMap.metaClass.multiPut << { key, value ->
//                delegate[key] = delegate[key] ?: []; delegate[key] += value
//            }

            def resMap = [:].withDefault { [] }
            String line
            while ((line = reader.readLine()) != null) {
                def delimeter = line.indexOf(' ')
                if (delimeter>0){
                    resMap[line.take(delimeter)]+=  line.drop(delimeter+1)
                }
            }
            reader.close()


            def writer = rFile.newWriter('UTF-8')
            writer.writeLine("package "+pack+";")
            writer.newLine()
            writer.writeLine("public final class R {")
            writer.writeLine("     private R() {}")
            writer.flush()

            resMap.keySet().forEach{ key ->



                writer.writeLine("     public static final class " + key + " {")
                writer.writeLine("     private " + key + "() {}")

                resMap[key].forEach { item ->
                    def items = item.split()
                    if (items.size() == 1)
                        writer.writeLine("        public static int " + item + ";")
                    else if (items.size() >= 2) {
                        def head = items[0]
                        writer.writeLine("        public static int[] " + head + "= new int[" + (items.size() - 1) + "];")
                        for (int i = 1; i < items.size(); i++) {
                            def style = items[i]
                            writer.writeLine("        public static int " + head + "_" + style + ";")
                        }

                    }
                }
                writer.writeLine("}")
                writer.flush()

            }

            writer.writeLine("}")
            writer.flush()
            writer.close()
        }

    }

    static void removeR(BaseVariant variant, File destinationDir){
        def pack = getProjectPackageName(variant)
        def packDir = pack.replace('.', File.separator)
        def resFile = new File(destinationDir, packDir)
        def rFile = new File(resFile, "R.class")

  //     println(">> remove r from "+destinationDir)
  //     println(">> remove r  "+rFile)

        for(File r: resFile.listFiles(new FilenameFilter() {
            @Override
            boolean accept(File dir, String name) {
                return name.startsWith('R$') &&  name.endsWith('.class')
            }
        })){
            r.delete()
        }

        rFile.delete()
    }
}
