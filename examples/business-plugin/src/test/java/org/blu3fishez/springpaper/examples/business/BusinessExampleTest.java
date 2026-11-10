package org.blu3fishez.springpaper.examples.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.blu3fishez.springpaper.annotation.PaperCommandExecutor;
import org.blu3fishez.springpaper.bootstrap.PaperCoreConfiguration;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.examples.business.inbox.*;
import org.blu3fishez.springpaper.jdbc.JdbcConfiguration;
import org.blu3fishez.springpaper.task.PaperTasks;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcOperations;

class BusinessExampleTest {
    record DefaultJdbcConsumer(JdbcOperations jdbc) {}
    record RequiredFeature(InboxRepository repository) {}
    @Test void dedicatedJdbcTuningCoexistsWithTheDefaultAndTheRealScheduledJobDeletesExpiredRows() throws Exception {
        try (var f = new Fixture(true)) {
            var defaultJdbc = f.context.getBean(DefaultJdbcConsumer.class).jdbc();
            assertNotSame(defaultJdbc, f.context.getBean("maintenanceJdbc"));
            defaultJdbc.update("INSERT INTO inbox VALUES (?, DATEADD('DAY', -1, CURRENT_TIMESTAMP))", new UUID(0, 0).toString());
            defaultJdbc.update("INSERT INTO inbox VALUES (?, DATEADD('DAY', 1, CURRENT_TIMESTAMP))", new UUID(0, 0).toString());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (defaultJdbc.queryForObject("SELECT COUNT(*) FROM inbox", Integer.class) != 1 && System.nanoTime() < deadline)
                Thread.sleep(20);
            assertEquals(1, defaultJdbc.queryForObject("SELECT COUNT(*) FROM inbox", Integer.class));
        }
    }
    @Test void consumerWorkerAndDecoratorAreUsedAndMainDeliveryCompletesTheReturnedResult() throws Exception {
        try (var f = new Fixture(true)) {
            LocaleContextHolder.setLocale(Locale.KOREAN);
            try {
                assertEquals(Locale.KOREAN, f.context.getBean(PaperTasks.class).async(LocaleContextHolder::getLocale).get(2, TimeUnit.SECONDS));
            } finally { LocaleContextHolder.resetLocaleContext(); }
            var player = f.player(UUID.randomUUID());
            var result = f.context.getBean(InboxService.class).show(player);
            Runnable delivery = f.deliveries.poll(2, TimeUnit.SECONDS); assertNotNull(delivery);
            assertFalse(result.isDone()); delivery.run(); result.get(2, TimeUnit.SECONDS);
            verify(player).sendPlainMessage("Pending mail: 0");
        }
    }
    @Test void staleConnectionsAndShutdownCannotSendLateMessages() throws Exception {
        try (var f = new Fixture(true)) {
            var id = UUID.randomUUID(); var old = f.player(id);
            var first = f.context.getBean(InboxService.class).show(old);
            Runnable stale = f.deliveries.poll(2, TimeUnit.SECONDS); assertNotNull(stale);
            var replacement = f.player(id); stale.run(); first.join();
            verify(old, never()).sendPlainMessage(anyString());
            var stopped = f.context.getBean(InboxService.class).show(replacement);
            Runnable late = f.deliveries.poll(2, TimeUnit.SECONDS); assertNotNull(late);
            f.context.close();
            assertTrue(stopped.isCompletedExceptionally()); late.run();
            verify(replacement, never()).sendPlainMessage(anyString());
        }
    }
    @Test void disabledFeatureHasNoPoolJobsOrAdaptersEvenWithAnAdditionalComponentScan() {
        try (var f = new Fixture(false, true)) {
            for (String bean : new String[] {"inboxDatabase", "inboxRepository", "inboxService", "inboxMaintenance"})
                assertFalse(f.context.containsBean(bean), bean);
            assertTrue(f.context.getBeansOfType(InboxCommand.class).isEmpty());
            assertTrue(f.context.getBeansOfType(InboxListener.class).isEmpty());
            assertFalse(f.context.getBeanFactory().containsSingleton("paperJdbcTemplate"));
            // Static descriptor metadata still exists in bytecode/build output even without a bean.
            assertEquals("paper-inbox", InboxCommand.class.getAnnotation(PaperCommandExecutor.class).commands()[0]);
        }
    }
    @Test void cancellingTheReturnedBusinessResultFencesTheQueuedMainMutation() throws Exception {
        try (var f = new Fixture(true)) {
            var player = f.player(UUID.randomUUID());
            var result = f.context.getBean(InboxService.class).show(player);
            Runnable late = f.deliveries.poll(2, TimeUnit.SECONDS); assertNotNull(late);
            assertTrue(result.cancel(false)); late.run();
            verify(player, never()).sendPlainMessage(anyString());
            assertTrue(result.isCancelled());
        }
    }
    @Test void requiredDependenciesOnDisabledFeaturesFailInsteadOfReceivingAFakeBean() {
        var f = new Fixture();
        try (var context = f.context) {
            context.registerBean(RequiredFeature.class);
            assertThrows(UnsatisfiedDependencyException.class, context::refresh);
        }
    }
    private static class Fixture implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final LinkedBlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        final Server server = mock(Server.class);
        Fixture(boolean enabled) { this(enabled, false); }
        Fixture(boolean enabled, boolean scan) {
            this();
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("feature", Map.of(
                    "example.inbox.enabled", enabled, "example.inbox.cleanup-delay", "PT0.05S")));
            if (scan) context.scan(InboxConfiguration.class.getPackageName());
            if (enabled) context.registerBean(DefaultJdbcConsumer.class);
            context.refresh();
        }
        Fixture() {
            var plugin = mock(JavaPlugin.class); var scheduler = mock(BukkitScheduler.class);
            when(plugin.getServer()).thenReturn(server); when(plugin.isEnabled()).thenReturn(true);
            when(plugin.namespace()).thenReturn("business"); when(plugin.getDataFolder()).thenReturn(new File("build/test-data"));
            when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
            when(server.getScheduler()).thenReturn(scheduler);
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                deliveries.add(call.getArgument(1)); return mock(BukkitTask.class);
            });
            PaperCoreConfiguration.registerHost(context, plugin, new PaperConfiguration(context.getEnvironment()));
            context.register(BusinessConfiguration.class, JdbcConfiguration.class);
        }
        Player player(UUID id) {
            var player = mock(Player.class); when(player.getUniqueId()).thenReturn(id);
            when(player.isOnline()).thenReturn(true); when(server.getPlayer(id)).thenReturn(player);
            return player;
        }
        @Override public void close() { context.close(); }
    }
}
