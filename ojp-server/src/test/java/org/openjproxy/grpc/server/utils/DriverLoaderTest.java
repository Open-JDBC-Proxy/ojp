package org.openjproxy.grpc.server.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverLoaderTest {

    @Test
    void testLoadDriversFromPath_NullPath() {
        // Should return true and not fail with null path
        assertTrue(DriverLoader.loadDriversFromPath(null));
    }

    @Test
    void testLoadDriversFromPath_EmptyPath() {
        // Should return true and not fail with empty path
        assertTrue(DriverLoader.loadDriversFromPath(""));
        assertTrue(DriverLoader.loadDriversFromPath("   "));
    }

    @Test
    void testLoadDriversFromPath_NonExistentDirectory(@TempDir Path tempDir) {
        // Should return true and NOT create the directory
        Path libsPath = tempDir.resolve("ojp-libs");
        assertFalse(Files.exists(libsPath));
        
        assertTrue(DriverLoader.loadDriversFromPath(libsPath.toString()));
        // Directory should NOT be created
        assertFalse(Files.exists(libsPath));
    }

    @Test
    void testLoadDriversFromPath_ExistingEmptyDirectory(@TempDir Path tempDir) throws IOException {
        // Create an empty directory
        Path libsPath = tempDir.resolve("ojp-libs");
        Files.createDirectory(libsPath);
        
        // Should return true even with no JARs
        assertTrue(DriverLoader.loadDriversFromPath(libsPath.toString()));
    }

    @Test
    void testLoadDriversFromPath_WithJarFiles(@TempDir Path tempDir) throws IOException {
        // Create a directory with a dummy JAR file
        Path libsPath = tempDir.resolve("ojp-libs");
        Files.createDirectory(libsPath);
        
        // Create a simple JAR file
        File jarFile = libsPath.resolve("dummy-driver.jar").toFile();
        createDummyJar(jarFile);
        
        // Should load the JAR successfully
        assertTrue(DriverLoader.loadDriversFromPath(libsPath.toString()));
    }

    @Test
    void testLoadDriversFromPath_FileInsteadOfDirectory(@TempDir Path tempDir) throws IOException {
        // Create a file instead of a directory
        Path filePath = tempDir.resolve("not-a-directory.txt");
        Files.createFile(filePath);
        
        // Should return false because it's not a directory
        assertFalse(DriverLoader.loadDriversFromPath(filePath.toString()));
    }

    @Test
    void testLoadDriversFromPath_MultipleJars(@TempDir Path tempDir) throws IOException {
        // Create a directory with multiple JAR files
        Path libsPath = tempDir.resolve("ojp-libs");
        Files.createDirectory(libsPath);
        
        // Create multiple JAR files
        createDummyJar(libsPath.resolve("driver1.jar").toFile());
        createDummyJar(libsPath.resolve("driver2.jar").toFile());
        createDummyJar(libsPath.resolve("driver3.jar").toFile());
        
        // Should load all JARs successfully
        assertTrue(DriverLoader.loadDriversFromPath(libsPath.toString()));
    }

    @Test
    void testLoadDriversFromPath_IgnoresNonJarFiles(@TempDir Path tempDir) throws IOException {
        // Create a directory with JAR and non-JAR files
        Path libsPath = tempDir.resolve("ojp-libs");
        Files.createDirectory(libsPath);
        
        // Create a JAR file
        createDummyJar(libsPath.resolve("driver.jar").toFile());
        
        // Create non-JAR files
        Files.createFile(libsPath.resolve("readme.txt"));
        Files.createFile(libsPath.resolve("config.xml"));
        
        // Should load only the JAR file successfully
        assertTrue(DriverLoader.loadDriversFromPath(libsPath.toString()));
    }

    /**
     * Creates a minimal valid JAR file for testing purposes
     */
    private void createDummyJar(File jarFile) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(jarFile);
             JarOutputStream jos = new JarOutputStream(fos)) {
            
            // Add a dummy entry to make it a valid JAR
            ZipEntry entry = new ZipEntry("META-INF/MANIFEST.MF");
            jos.putNextEntry(entry);
            jos.write("Manifest-Version: 1.0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jos.closeEntry();
        }
    }
}
