package org.blu3fishez.springpaper.examples.business.inbox;

import javax.sql.DataSource;
import org.blu3fishez.springpaper.task.PaperTasks;
import org.bukkit.Server;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.*;

@Configuration(proxyBeanMethods = false)
@Conditional(InboxEnabled.class)
@Import({InboxCommand.class, InboxListener.class})
public class InboxConfiguration {
    @Bean(destroyMethod = "shutdown") EmbeddedDatabase inboxDatabase() {
        return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:inbox-schema.sql").build();
    }
    @Bean(defaultCandidate = false) @Lazy
    JdbcOperations maintenanceJdbc(DataSource inboxDatabase) {
        var jdbc = new JdbcTemplate(inboxDatabase);
        jdbc.setQueryTimeout(3); // A dedicated tuning choice does not replace the normal default.
        return jdbc;
    }
    @Bean InboxRepository inboxRepository(JdbcClient client) { return new InboxRepository(client); }
    @Bean InboxService inboxService(InboxRepository repository, PaperTasks execution, Server server,
            java.util.logging.Logger logger) { return new InboxService(repository, execution, server, logger); }
    @Bean InboxMaintenance inboxMaintenance(@Qualifier("maintenanceJdbc") JdbcOperations jdbc) {
        return new InboxMaintenance(jdbc);
    }
}
