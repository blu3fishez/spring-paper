package org.blu3fishez.springpaper.examples.jdbc;

import org.blu3fishez.springpaper.bootstrap.PaperCoreConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration(proxyBeanMethods = false)
@Import(PaperCoreConfiguration.class)
@ComponentScan(basePackageClasses = JdbcExampleConfiguration.class)
@EnableTransactionManagement
public class JdbcExampleConfiguration {
    @Bean org.springframework.transaction.PlatformTransactionManager transactionManager(javax.sql.DataSource database) {
        // 최초 native 검증에서 매니저 없이 @Transactional을 호출해 NoSuchBeanDefinitionException이 났다.
        // JDBC 전용 앱이 매니저를 선택한다. JPA와 함께 쓰면 기존 JpaTransactionManager를 재사용한다.
        return new org.springframework.jdbc.support.JdbcTransactionManager(database);
    }
    @Bean(destroyMethod = "shutdown") EmbeddedDatabase exampleDataSource() {
        // Consumer-owned DataSource: no JPA, Hibernate, external server or credentials are required.
        return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:jdbc-schema.sql").build();
    }
}
