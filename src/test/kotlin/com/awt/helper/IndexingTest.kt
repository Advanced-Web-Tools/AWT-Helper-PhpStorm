package com.awt.helper

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.execution.RunManager
import com.jetbrains.php.config.library.PhpIncludePathManager
import com.jetbrains.php.PhpIndex
import java.nio.file.*

class IndexingTest : BasePlatformTestCase() {
    fun testExcludedAwtWithExplicitPhpLibraryAndNoDuplicates() {
        val root = Files.createTempDirectory("awt-index-test-")
        try {
            val framework=root.resolve(".awt/framework")
            for(file in listOf("awt","public/index.php","bootstrap/boot.php","awt_src/classes/package/manifest/reader/ManifestReader.php","awt_data/config/awt_db.php","awt_data/config/awt_config.php")) {
                Files.createDirectories(framework.resolve(file).parent); Files.writeString(framework.resolve(file),"<?php")
            }
            val php=framework.resolve("awt_src/classes/AwtHelperIndexFixture.php")
            Files.writeString(php,"<?php class AwtHelperIndexFixture {}")
            val ignored=root.resolve(".awt/helper/ShouldNotIndex.php")
            Files.createDirectories(ignored.parent); Files.writeString(ignored,"<?php class AwtHelperExcludedFixture {}")
            val dependency = root.resolve(".awt/dependencies/Core")
            Files.createDirectories(dependency)
            Files.writeString(dependency.resolve("manifest.json"), json.toJson(Manifest(name = "Core")))
            val dependencyPhp = dependency.resolve("DownloadedCore.php")
            Files.writeString(dependencyPhp, "<?php class AwtHelperDependencyFixture {}")
            val vf=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!
            ModuleRootModificationUtil.addContentRoot(module,vf.path)
            val config=HelperConfig(frameworkPath=framework.toString(),mode=FrameworkMode.REFERENCE)
            Integration.sync(project,config,root)
            Integration.sync(project,config,root)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val index=ProjectFileIndex.getInstance(project)
            val excluded=LocalFileSystem.getInstance().findFileByNioFile(ignored)!!
            val libraryFile=LocalFileSystem.getInstance().findFileByNioFile(php)!!
            assertTrue(ModuleRootManager.getInstance(module).contentEntries.any { entry -> entry.excludeFolderUrls.any { it.endsWith("/.awt") } })
            assertTrue(index.isExcluded(excluded))
            assertFalse(index.isInContent(libraryFile))
            assertTrue(index.isInLibrarySource(libraryFile))
            assertEquals(1,PhpIncludePathManager.getInstance(project).includePath.count { it == framework.toString() })
            assertEquals(1,PhpIndex.getInstance(project).getClassesByFQN("\\AwtHelperIndexFixture").size)
            assertEquals(0,PhpIndex.getInstance(project).getClassesByFQN("\\AwtHelperExcludedFixture").size)
            val dependencyFile = LocalFileSystem.getInstance().findFileByNioFile(dependencyPhp)!!
            assertFalse(index.isInContent(dependencyFile))
            assertTrue(index.isInLibrarySource(dependencyFile))
            assertEquals(1, PhpIndex.getInstance(project).getClassesByFQN("\\AwtHelperDependencyFixture").size)
            assertEquals(1, PhpIncludePathManager.getInstance(project).includePath.count { it == dependency.toString() })
            assertEquals(listOf(dependency.toString()), config.managedDependencyIncludePaths)
            assertEquals(2,RunManager.getInstance(project).allSettings.count { (it.configuration as? AwtConfiguration)?.managedId in listOf("build","deploy") })
            Files.writeString(framework.resolve("awt_data/config/awt_db.php"),"<?php const DB_TYPE = 'mysql';")
            for(mode in listOf(FrameworkMode.EXISTING,FrameworkMode.INSTALLED,FrameworkMode.REFERENCE)) {
                config.mode=mode
                Integration.sync(project,config,root)
                Integration.sync(project,config,root)
                assertEquals(1,PhpIncludePathManager.getInstance(project).includePath.count { it == framework.toString() })
                assertTrue(ProjectFileIndex.getInstance(project).isInLibrarySource(libraryFile))
                assertEquals(if(mode == FrameworkMode.REFERENCE) 2 else 3,RunManager.getInstance(project).allSettings.count { (it.configuration as? AwtConfiguration)?.managedId in listOf("build","deploy","server") })
            }
            val replacement=root.resolve("other-framework")
            Files.move(framework,replacement)
            config.frameworkPath=replacement.toString()
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
            Integration.sync(project,config,root)
            assertFalse(PhpIncludePathManager.getInstance(project).includePath.contains(framework.toString()))
            assertTrue(PhpIncludePathManager.getInstance(project).includePath.contains(replacement.toString()))
            assertTrue(ModuleRootManager.getInstance(module).contentEntries.any { it.excludeFolderUrls.any { url -> url.endsWith("/other-framework") } })
            Archives.deleteTree(dependency)
            Integration.sync(project, config, root)
            assertFalse(PhpIncludePathManager.getInstance(project).includePath.contains(dependency.toString()))
            assertTrue(config.managedDependencyIncludePaths.isEmpty())
        } finally {
            ApplicationManager.getApplication().runWriteAction {
                PhpIncludePathManager.getInstance(project).includePath=emptyList()
                val model=ModuleRootManager.getInstance(module).modifiableModel
                model.contentEntries.filter { it.file?.path == root.toString() }.forEach(model::removeContentEntry)
                model.commit()
            }
            Archives.deleteTree(root)
        }
    }
}
