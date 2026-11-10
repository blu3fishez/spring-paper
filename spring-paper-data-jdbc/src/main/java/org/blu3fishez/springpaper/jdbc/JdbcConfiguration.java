package org.blu3fishez.springpaper.jdbc;

import javax.sql.DataSource;
import org.blu3fishez.springpaper.config.internal.DefaultBeans;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Standard Spring JDBC facades. DataSource and transaction-manager selection belong to the consumer. */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
public class JdbcConfiguration {
    @Bean @Lazy @Fallback
    public JdbcTemplate paperJdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
    @Bean @Lazy @Fallback
    public NamedParameterJdbcTemplate paperNamedJdbcTemplate(JdbcOperations operations) {
        return new NamedParameterJdbcTemplate(operations);
    }
    @Bean @Lazy @Fallback
    public JdbcClient paperJdbcClient(NamedParameterJdbcOperations operations) { return JdbcClient.create(operations); }

    @Bean public static BeanFactoryPostProcessor springPaperJdbcInfrastructure() {
        return factory -> {
            // A qualifier-only reporting adapter is not a default. Reproduced with the unqualified
            // consumer injection failing despite a valid DataSource. Use the actual Spring resolver.
            DefaultBeans.backOff(factory, JdbcOperations.class, "paperJdbcTemplate", "jdbcTemplate");
            DefaultBeans.backOff(factory, NamedParameterJdbcOperations.class, "paperNamedJdbcTemplate", "namedParameterJdbcTemplate");
            DefaultBeans.backOff(factory, JdbcClient.class, "paperJdbcClient", "jdbcClient");
            // A JDBC default manager could steal JPA transaction ownership. The consumer selects it.
        };
    }
}
