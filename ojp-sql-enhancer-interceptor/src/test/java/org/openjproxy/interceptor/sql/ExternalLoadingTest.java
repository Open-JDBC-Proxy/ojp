package org.openjproxy.interceptor.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjproxy.interceptor.RequestInterceptor;
import org.openjproxy.interceptor.RequestInterceptorRegistry;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests external loading of SQL Enhancer Interceptor from a JAR file,
 * simulating deployment to ojp-libs/ directory.
 */
class ExternalLoadingTest {
    
    @Test
    void testLoadInterceptorFromExternalJar(@TempDir Path tempDir) throws IOException {
        // Find the shaded JAR in target directory
        Path targetDir = Path.of("target");
        File[] shadedJars = targetDir.toFile().listFiles((dir, name) -> 
            name.endsWith("-shaded.jar"));
        
        assertThat(shadedJars)
            .as("Shaded JAR should exist in target directory")
            .isNotNull()
            .isNotEmpty();
        
        Path shadedJar = shadedJars[0].toPath();
        
        // Copy to temp directory (simulating ojp-libs/)
        Path ojpLibs = tempDir.resolve("ojp-libs");
        Files.createDirectories(ojpLibs);
        Path externalJar = ojpLibs.resolve(shadedJar.getFileName());
        Files.copy(shadedJar, externalJar, StandardCopyOption.REPLACE_EXISTING);
        
        // Verify JAR was copied
        assertThat(externalJar).exists();
        assertThat(externalJar.toFile().length()).isGreaterThan(1_000_000); // Should be >1MB
        
        // Create a new classloader with the external JAR
        URL[] urls = new URL[]{externalJar.toUri().toURL()};
        URLClassLoader classLoader = new URLClassLoader(urls, getClass().getClassLoader());
        
        // Use ServiceLoader to discover interceptors
        ServiceLoader<RequestInterceptor> serviceLoader = 
            ServiceLoader.load(RequestInterceptor.class, classLoader);
        
        // Find SqlEnhancerInterceptor
        List<RequestInterceptor> interceptors = serviceLoader.stream()
            .map(ServiceLoader.Provider::get)
            .toList();
        
        assertThat(interceptors)
            .as("Should discover SQL Enhancer Interceptor from external JAR")
            .isNotEmpty()
            .anyMatch(i -> "sql-enhancer".equals(i.id()));
        
        // Verify interceptor properties
        RequestInterceptor sqlEnhancer = interceptors.stream()
            .filter(i -> "sql-enhancer".equals(i.id()))
            .findFirst()
            .orElseThrow();
        
        assertThat(sqlEnhancer.getPriority())
            .as("SQL Enhancer should have priority 600")
            .isEqualTo(600);
        
        // Clean up
        classLoader.close();
    }
    
    @Test
    void testInterceptorDiscoveryFromExternalJar(@TempDir Path tempDir) throws IOException {
        // Find the shaded JAR
        Path targetDir = Path.of("target");
        File[] shadedJars = targetDir.toFile().listFiles((dir, name) -> 
            name.endsWith("-shaded.jar"));
        
        assertThat(shadedJars).isNotNull().isNotEmpty();
        Path shadedJar = shadedJars[0].toPath();
        
        // Copy to temp directory (simulating ojp-libs/)
        Path ojpLibs = tempDir.resolve("ojp-libs");
        Files.createDirectories(ojpLibs);
        Path externalJar = ojpLibs.resolve(shadedJar.getFileName());
        Files.copy(shadedJar, externalJar, StandardCopyOption.REPLACE_EXISTING);
        
        // Create classloader with external JAR
        URL[] urls = new URL[]{externalJar.toUri().toURL()};
        URLClassLoader classLoader = new URLClassLoader(urls, getClass().getClassLoader());
        
        // Use ServiceLoader with the custom classloader
        ServiceLoader<RequestInterceptor> serviceLoader = 
            ServiceLoader.load(RequestInterceptor.class, classLoader);
        
        List<RequestInterceptor> allInterceptors = serviceLoader.stream()
            .map(ServiceLoader.Provider::get)
            .toList();
        
        assertThat(allInterceptors)
            .as("Should discover interceptor from external JAR")
            .isNotEmpty()
            .anyMatch(i -> "sql-enhancer".equals(i.id()));
        
        // Verify priority is in transformation range
        RequestInterceptor sqlEnhancer = allInterceptors.stream()
            .filter(i -> "sql-enhancer".equals(i.id()))
            .findFirst()
            .orElseThrow();
        
        assertThat(sqlEnhancer.getPriority())
            .as("SQL Enhancer should be in transformation range (500-999)")
            .isBetween(500, 999);
        
        // Clean up
        classLoader.close();
    }
    
    @Test
    void testShadedJarContainsAllDependencies(@TempDir Path tempDir) throws IOException {
        // Find the shaded JAR
        Path targetDir = Path.of("target");
        File[] shadedJars = targetDir.toFile().listFiles((dir, name) -> 
            name.endsWith("-shaded.jar"));
        
        assertThat(shadedJars).isNotNull().isNotEmpty();
        Path shadedJar = shadedJars[0].toPath();
        
        // Verify JAR size indicates it contains dependencies
        long jarSize = Files.size(shadedJar);
        assertThat(jarSize)
            .as("Shaded JAR should be large (contains Calcite and dependencies)")
            .isGreaterThan(30_000_000); // Should be >30MB with all dependencies
        
        // Create classloader with ONLY the shaded JAR (no parent classpath)
        URL[] urls = new URL[]{shadedJar.toUri().toURL()};
        URLClassLoader isolatedClassLoader = new URLClassLoader(urls, null);
        
        // Try to load SqlEnhancerInterceptor class
        try {
            Class<?> interceptorClass = isolatedClassLoader.loadClass(
                "org.openjproxy.interceptor.sql.SqlEnhancerInterceptor");
            
            assertThat(interceptorClass)
                .as("Should be able to load interceptor class from shaded JAR")
                .isNotNull();
            
            // Try to load a Calcite class (should be shaded/relocated)
            Class<?> calciteClass = isolatedClassLoader.loadClass(
                "org.openjproxy.shaded.calcite.sql.SqlNode");
            
            assertThat(calciteClass)
                .as("Should be able to load shaded Calcite classes")
                .isNotNull();
            
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Shaded JAR should contain all dependencies", e);
        } finally {
            isolatedClassLoader.close();
        }
    }
    
    @Test
    void testServiceLoaderMetadataInShadedJar(@TempDir Path tempDir) throws IOException {
        // Find the shaded JAR
        Path targetDir = Path.of("target");
        File[] shadedJars = targetDir.toFile().listFiles((dir, name) -> 
            name.endsWith("-shaded.jar"));
        
        assertThat(shadedJars).isNotNull().isNotEmpty();
        Path shadedJar = shadedJars[0].toPath();
        
        // Create classloader
        URL[] urls = new URL[]{shadedJar.toUri().toURL()};
        URLClassLoader classLoader = new URLClassLoader(urls, getClass().getClassLoader());
        
        // Check if META-INF/services file exists
        URL serviceFile = classLoader.getResource(
            "META-INF/services/org.openjproxy.interceptor.RequestInterceptor");
        
        assertThat(serviceFile)
            .as("Shaded JAR should contain ServiceLoader metadata")
            .isNotNull();
        
        // Read the service file content
        try (var stream = serviceFile.openStream()) {
            String content = new String(stream.readAllBytes());
            
            assertThat(content)
                .as("Service file should register SqlEnhancerInterceptor")
                .contains("org.openjproxy.interceptor.sql.SqlEnhancerInterceptor");
        }
        
        classLoader.close();
    }
}
