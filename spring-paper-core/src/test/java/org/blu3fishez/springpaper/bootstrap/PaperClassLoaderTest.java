package org.blu3fishez.springpaper.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import org.blu3fishez.springpaper.fixture.resources.ResourceConfiguration;
import org.bukkit.command.CommandExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class PaperClassLoaderTest {
    private static final String FIXTURES = "org.blu3fishez.springpaper.fixture.resources.";

    @Test void sameJava25FixtureWorksWhenPlatformResourcesAreVisible() throws Exception {
        assertNotNull(CommandExecutor.class.getClassLoader().getResource("org/bukkit/command/CommandExecutor.class"));
        try (var context = new AnnotationConfigApplicationContext(ResourceConfiguration.class)) {
            assertEquals(1, context.getBeansOfType(CommandExecutor.class).size());
            assertCachedProxy(context.getBean(CommandExecutor.class));
        }
    }

    @Test void scansPluginComponentsWhosePaperInterfacesAreParentOnlyResources() throws Exception {
        ClassLoader platform = CommandExecutor.class.getClassLoader();
        URL[] urls = { ResourceConfiguration.class.getProtectionDomain().getCodeSource().getLocation() };
        try (var plugin = new PluginOnlyResources(urls, platform);
             var context = new AnnotationConfigApplicationContext()) {
            assertSame(CommandExecutor.class, plugin.loadClass(CommandExecutor.class.getName()));
            assertNull(plugin.getResource("org/bukkit/command/CommandExecutor.class"));
            context.setClassLoader(plugin);
            context.setResourceLoader(new PaperResourceLoader(plugin, platform));
            assertSame(plugin, context.getClassLoader());
            context.register(plugin.loadClass(ResourceConfiguration.class.getName()));
            assertDoesNotThrow(context::refresh, "Spring must read Paper interface metadata without bundling Paper classes");
            assertEquals(1, context.getBeansOfType(CommandExecutor.class).size());
            CommandExecutor bean = context.getBean(CommandExecutor.class);
            assertSame(plugin, bean.getClass().getClassLoader());
            assertCachedProxy(bean);
        }
    }

    @Test void resourcesKeepPluginPrecedenceAndDeduplicateTheSameUrl(@TempDir Path root) throws Exception {
        Path local = Files.createDirectory(root.resolve("plugin"));
        Path host = Files.createDirectory(root.resolve("platform"));
        Files.writeString(local.resolve("shared.txt"), "plugin");
        Files.writeString(host.resolve("shared.txt"), "platform");
        Files.writeString(host.resolve("host-only.txt"), "host-only");
        try (var plugin = new URLClassLoader(new URL[] { local.toUri().toURL() }, null);
             var platform = new URLClassLoader(new URL[] { host.toUri().toURL() }, null)) {
            var resources = new PaperResourceLoader(plugin, platform);
            assertEquals("plugin", resources.getResource("classpath:shared.txt").getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("host-only", resources.getResource("classpath:host-only.txt").getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            var matches = resources.getResources("classpath*:shared.txt");
            assertEquals(2, matches.length);
            assertEquals(local.resolve("shared.txt").toUri().toURL(), matches[0].getURL());
            assertEquals(host.resolve("shared.txt").toUri().toURL(), matches[1].getURL());
            assertEquals(1, new PaperResourceLoader(plugin, plugin).getResources("classpath*:shared.txt").length);
        }
    }

    private static void assertCachedProxy(CommandExecutor bean) throws Exception {
        assertTrue(AopUtils.isCglibProxy(bean));
        assertTrue(bean.onCommand(null, null, "same-command", new String[0]));
        assertTrue(bean.onCommand(null, null, "same-command", new String[0]));
        assertEquals(1, bean.getClass().getMethod("invocations").invoke(bean));
    }

    /** Models Paper's local-only resource lookup while Java class loading still delegates. */
    private static final class PluginOnlyResources extends URLClassLoader {
        PluginOnlyResources(URL[] urls, ClassLoader platform) { super(urls, platform); }
        @Override public URL getResource(String name) { return findResource(name); }
        @Override public Enumeration<URL> getResources(String name) throws IOException { return findResources(name); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (!name.startsWith(FIXTURES)) return super.loadClass(name, resolve);
                Class<?> found = findLoadedClass(name);
                if (found == null) found = findClass(name);
                if (resolve) resolveClass(found);
                return found;
            }
        }
    }
}
