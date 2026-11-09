package org.blu3fishez.springpaper.jdbc;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    @Bean public static BeanFactoryPostProcessor springPaperJdbcInfrastructure() {
        return factory -> {
            var registry = (BeanDefinitionRegistry) factory;
            if (factory.getBeanNamesForType(JdbcOperations.class, false, false).length == 0) {
                registry.registerBeanDefinition("jdbcTemplate", new RootBeanDefinition(JdbcTemplate.class,
                        () -> new JdbcTemplate(factory.getBean(DataSource.class))));
            }
            if (factory.getBeanNamesForType(NamedParameterJdbcOperations.class, false, false).length == 0) {
                registry.registerBeanDefinition("namedParameterJdbcTemplate", new RootBeanDefinition(NamedParameterJdbcTemplate.class,
                        () -> new NamedParameterJdbcTemplate(factory.getBean(JdbcOperations.class))));
            }
            if (factory.getBeanNamesForType(JdbcClient.class, false, false).length == 0) {
                registry.registerBeanDefinition("jdbcClient", new RootBeanDefinition(JdbcClient.class,
                        () -> JdbcClient.create(factory.getBean(NamedParameterJdbcOperations.class))));
            }
            // A JDBC default manager created first would steal JPA's transaction ownership. The application
            // selects its standard manager; enabling both modules never creates another transaction owner.
        };
    }
}
