package com.awt.helper

import org.junit.Test
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.*
import java.util.zip.*

class CoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun zip(vararg entries: Pair<String, ByteArray>): Path {
        val file = temporary.newFile().toPath()
        ZipOutputStream(Files.newOutputStream(file)).use { z -> entries.forEach { (name,data) -> z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() } }
        return file
    }
    private fun text(s: String) = s.toByteArray()
    private fun fails(block: () -> Unit) { try { block(); fail("Expected validation failure") } catch(e: RuntimeException) { assertTrue(e.message!!.isNotEmpty()) } }
    @Test fun manifestRoundtripAndNullableFields() {
        val m=Manifest(name="Blog",maximum_awt_version=null,author=null,dependencies=listOf(Dependency("Core",">=27.0.0 <28.0.0","https://example.com/core.zip")))
        assertEquals(m,Manifest.read(json.toJson(m)))
        assertTrue(json.toJson(m).contains("\"maximum_awt_version\": null"))
        val booleanJson=json.toJson(m).replace("\"system_package\": 0", "\"system_package\": true")
        assertEquals(1,Manifest.read(booleanJson).system_package)
    }
    @Test fun validatesManifestSchemaAndPhpIdentifiers() {
        listOf("../Blog","1Blog","My Blog","My-Blog","CON","class").forEach { n -> fails { Manifest(name=n).validate() } }
        fails { Manifest(name="Blog",version="1.2.3.4").validate() }
        fails { Manifest(name="Blog",type=3).validate() }
        fails { Manifest(name="Blog",dependencies=listOf(Dependency("Blog"))).validate() }
        fails { Manifest(name="Blog",dependencies=listOf(Dependency("Core"),Dependency("Core"))).validate() }
        fails { Manifest(name="Blog",dependencies=listOf(Dependency("Core","^1.0"))).validate() }
        val valid=json.toJson(Manifest(name="Blog"))
        fails { Manifest.read(valid.replace("\"type\": 1","\"type\": \"1\"")) }
        fails { Manifest.read(valid.replace("\"dependencies\": []","\"dependencies\": {}")) }
        fails { Manifest.read(valid.replace("\"author\": \"Your Name\"","\"author\": false")) }
    }
    @Test fun templateReplacementPreservesSuffixAndBinary() {
        val root=temporary.newFolder().toPath()
        Files.createDirectories(root.resolve("PackageNameInstall"))
        Files.writeString(root.resolve("PackageNameInstall/PackageNameRouter.php"),"class PackageNameInstall {} // AnotherPackageNameSuffix")
        val binary=byteArrayOf(0,80,97,99,107,97,103,101,78,97,109,101)
        Files.write(root.resolve("icon.bin"),binary)
        Archives.replaceTemplate(root,"Blog")
        assertEquals("class BlogInstall {} // AnotherBlogSuffix",Files.readString(root.resolve("BlogInstall/BlogRouter.php")))
        assertArrayEquals(binary,Files.readAllBytes(root.resolve("icon.bin")))
    }
    @Test fun extractionNormalizesWrappersAndRejectsTraversal() {
        val root=temporary.newFolder().toPath()
        val archive=zip("release/wrapper/manifest.json" to text("{}"),"release/wrapper/main.php" to text("<?php"))
        Archives.extract(archive,root.resolve("package"),"manifest.json")
        assertTrue(Files.isRegularFile(root.resolve("package/main.php")))
        for (unsafe in listOf("../escape","/escape","C:/escape","wrapper/../../escape","wrapper\\escape")) {
            val evil=zip("wrapper/manifest.json" to text("{}"),unsafe to text("bad"))
            fails { Archives.extract(evil,root.resolve("evil"),"manifest.json") }
            assertFalse(Files.exists(root.resolve("evil")))
        }
        fails { Archives.extract(archive,root.resolve("package"),"manifest.json") }
    }
    @Test fun generatesProjectWithoutChangingOtherConstants() {
        val root=temporary.newFolder().toPath()
        val template=zip("manifest.json" to text("{}"),"PackageNameInstall.php" to text("class PackageNameInstall {}"),"main.php" to text("<?php"))
        val constants="<?php\ndefine('NAME', '');\ndefine('OTHER', 'PackageName');\n"
        val build=zip("wrapper/build/runtime/constants.php" to text(constants),"wrapper/build/dev_env.json" to text("{\"untouched\":true,\"devSecret\":\"bad-default\"}"),"wrapper/build/runtime/run.php" to text("<?php"))
        val framework=zip("fw/awt" to text("<?php"),"fw/public/index.php" to text("<?php"))
        val config=HelperConfig(frameworkPath=root.resolve(".awt/framework").toString(),mode=FrameworkMode.REFERENCE,frameworkVersion="27.0.0",templateVersion="2.0.0",buildVersion="2.0.0")
        val icon = temporary.newFile("chosen.PNG").toPath()
        val preview = temporary.newFile("cover.SVG").toPath()
        Files.write(icon, byteArrayOf(1, 2, 3))
        Files.writeString(preview, "<svg xmlns=\"http://www.w3.org/2000/svg\"/>")
        ProjectFiles.prepare(root,Manifest(name="Blog"),config,template,build,framework, PackageImages(icon, preview))
        val generatedManifest = Manifest.read(Files.readString(root.resolve("Blog/manifest.json")))
        assertEquals("data/icon/icon.png", generatedManifest.icon)
        assertEquals("data/preview/preview.svg", generatedManifest.preview_image)
        assertArrayEquals(Files.readAllBytes(icon), Files.readAllBytes(root.resolve("Blog/data/icon/icon.png")))
        assertEquals(Files.readString(preview), Files.readString(root.resolve("Blog/data/preview/preview.svg")))
        assertTrue(Files.isRegularFile(root.resolve("Blog/BlogInstall.php")))
        assertTrue(Files.isRegularFile(root.resolve(".awt/framework/public/index.php")))
        assertTrue(Files.isRegularFile(root.resolve("build/runtime/run.php")))
        assertEquals(constants.replace("define('NAME', '');","define('NAME', 'Blog');"),Files.readString(root.resolve("build/runtime/constants.php")))
        assertEquals("27.0.0",HelperConfig.read(root)!!.frameworkVersion)
        assertEquals("Blog",HelperConfig.read(root)!!.packageName)
        val dev=Files.readString(root.resolve("build/dev_env.json"))
        assertTrue(dev.contains("\"untouched\": true")); assertFalse(dev.contains("bad-default"))
        assertFalse(Files.readString(root.resolve(".awt/helper/helper.json")).contains("devSecret"))
        assertEquals("'a\\'b\\\\c'",phpString("a'b\\c"))
    }
    @Test fun releaseSelectionUsesConcreteVersionAndNeverMain() {
        val releases=listOf(ReleaseClient.Release("27.0.0",emptyList()),ReleaseClient.Release("26.0.0",emptyList()))
        assertEquals("27.0.0",ReleaseClient.select(releases,"Latest").version)
        assertEquals("26.0.0",ReleaseClient.select(releases,"26.0.0").version)
        fails { ReleaseClient.select(releases,"main") }
        fails { ReleaseClient.select(emptyList(),"Latest") }
    }
    @Test fun buildAndDeploymentUseSupportedArgumentsAndInput() {
        assertEquals(listOf("build/runtime/run.php"),Commands.build(false,false))
        assertEquals(listOf("build/runtime/run.php","--fast"),Commands.build(false,true))
        assertEquals(listOf("build/runtime/run.php","--install","--action=update","--fast"),Commands.build(true,true,"update"))
        assertEquals("n\n",Commands.input(false)); assertEquals("",Commands.input(true))
        assertFalse(Commands.build(false,true).contains("--install"))
    }
    @Test fun runModesAndDatabasePortPreserveUrlPath() {
        assertTrue(Commands.runIsBuild(FrameworkMode.REFERENCE))
        assertFalse(Commands.runIsBuild(FrameworkMode.EXISTING)); assertFalse(Commands.runIsBuild(FrameworkMode.INSTALLED))
        assertEquals(listOf("-S","localhost:3000"),Commands.server(HelperConfig()))
        val db=DatabaseInput("db.local",3307,"existing","user","p'\\x","My Website","/sajt1/public")
        db.validate()
        assertEquals(listOf("install","mysql","existing","db.local;port=3307","user","p'\\x","My Website","/sajt1/public"),db.installerArguments())
        fails { db.copy(name="evil;dbname=other").validate() }
        fails { db.copy(hostPath="C:\\folder").validate() }
    }
    @Test fun redactsSecretsAcrossProcessOutputChunks() {
        val filter=Redactor("0123456789secret")
        assertEquals("",filter.append("Error: 012345"))
        assertEquals("Error: [redacted]\n",filter.append("6789secret\n"))
        assertEquals("",filter.append("Tail 0123456789secret"))
        assertEquals("Tail [redacted]",filter.finish())
    }
    @Test fun localServerMapsUrlSubfolderWithoutUsingItAsWorkingDirectory() {
        val root=temporary.newFolder().toPath()
        val config=HelperConfig(hostnamePath="/sajt1/public",frameworkPath="/external/framework")
        assertEquals("http://localhost:3000/sajt1/public/",Server.browserUrl(config))
        val arguments=Server.arguments(root,config)
        assertEquals(listOf("-S","localhost:3000"),arguments.take(2))
        val router=Path.of(arguments.last())
        assertTrue(Files.isRegularFile(router)); assertTrue(router.startsWith(root.resolve(".awt/helper")))
        assertTrue(Files.readString(router).contains("'/sajt1/public'"))
        assertEquals(arguments,Server.arguments(root,config))
        assertEquals(listOf("-S","localhost:3000"),Server.arguments(root,config.copy(hostnamePath="/")))
    }

}
