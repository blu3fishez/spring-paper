package org.blu3fishez.springpaper.jpa;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.util.LinkedHashMap;
import java.util.List;
import javax.sql.DataSource;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.spi.PaperThreadAccess;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
@Import(JpaRepositoryScanning.class)
public class JpaConfiguration {
    /** Runs after user configurations are parsed, so existing resources are reused, not shadowed. */
    @Bean public static BeanFactoryPostProcessor springPaperJpaInfrastructure(PaperConfiguration properties) {
        return factory -> {
            BeanDefinitionRegistry registry = (BeanDefinitionRegistry) factory;
            // FactoryBean 제품의 singleton 여부는 초기화 전 확정되지 않을 수 있다. singleton으로
            // 조회를 제한하면 표준 LocalContainerEntityManagerFactoryBean 선언도 누락되므로,
            // 조기 생성 없이 Spring이 예측한 제품 타입으로 소비자 정의를 먼저 찾는다.
            String[] factories = factory.getBeanNamesForType(EntityManagerFactory.class, true, false);
            if (factories.length == 0 && !registry.containsBeanDefinition("entityManagerFactory")) {
                // 소비자의 EMF는 자체 DataSource/JNDI/JTA 구성을 소유할 수 있다. 먼저 풀을 만들면
                // 사용하지 않을 URL을 강제하거나 관련 없는 다중 DataSource 선택 때문에 시작이 실패한다.
                // 기본 EMF를 실제로 생성하는 경로에서만 기본 풀과 DataSource 선택을 준비한다.
                String[] sources = factory.getBeanNamesForType(DataSource.class, false, false);
                String source;
                if (sources.length == 0) {
                    source = "springPaperDataSource";
                    RootBeanDefinition definition = new RootBeanDefinition(HikariDataSource.class,
                            () -> createDataSource(properties));
                    definition.setDestroyMethodName("close");
                    registry.registerBeanDefinition(source, definition);
                } else source = choose(properties, "spring.paper.jpa.data-source-bean", sources);
                List<String> packages = properties.strings("spring.paper.jpa.entities");
                if (packages.isEmpty()) throw new IllegalArgumentException("spring.paper.jpa.entities is required");
                var values = new LinkedHashMap<String, Object>(properties.children("spring.paper.jpa.properties"));
                values.putIfAbsent("hibernate.hbm2ddl.auto", "validate");
                RootBeanDefinition definition = new RootBeanDefinition(LocalContainerEntityManagerFactoryBean.class);
                definition.getPropertyValues().add("dataSource", new RuntimeBeanReference(source));
                definition.getPropertyValues().add("packagesToScan", packages.toArray(String[]::new));
                definition.getPropertyValues().add("jpaVendorAdapter", new HibernateJpaVendorAdapter());
                definition.getPropertyValues().add("jpaPropertyMap", values);
                definition.getPropertyValues().add("persistenceUnitName", properties.text("spring.paper.jpa.persistence-unit", "spring-paper"));
                registry.registerBeanDefinition("entityManagerFactory", definition);
            } else if (factories.length > 0) {
                String existing = choose(properties, "spring.paper.jpa.entity-manager-factory-bean", factories);
                if (!existing.equals("entityManagerFactory")) registry.registerAlias(existing, "entityManagerFactory");
            }

            String[] managers = factory.getBeanNamesForType(PlatformTransactionManager.class, false, false);
            if (managers.length == 0) {
                registry.registerBeanDefinition("transactionManager", new RootBeanDefinition(JpaTransactionManager.class, () -> {
                    EntityManagerFactory entities = factory.getBean("entityManagerFactory", EntityManagerFactory.class);
                    PaperThreadAccess threads = factory.getBeanProvider(PaperThreadAccess.class).getIfAvailable();
                    return threads == null ? new JpaTransactionManager(entities) : PaperJpaTransactions.workerOnly(entities, threads);
                }));
            } else {
                String existing = choose(properties, "spring.paper.jpa.transaction-manager-bean", managers);
                if (!existing.equals("transactionManager")) registry.registerAlias(existing, "transactionManager");
            }
        };
    }

    private static String choose(PaperConfiguration config, String key, String[] names) {
        String selected = config.text(key, "");
        if (!selected.isBlank()) {
            if (!List.of(names).contains(selected)) throw new IllegalArgumentException(key + " is not an existing bean: " + selected);
            return selected;
        }
        if (names.length != 1) throw new IllegalArgumentException("Multiple resources; configure " + key);
        return names[0];
    }
    private static HikariDataSource createDataSource(PaperConfiguration config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.required("spring.paper.jpa.datasource.url"));
        hikari.setUsername(config.text("spring.paper.jpa.datasource.username", ""));
        hikari.setPassword(config.text("spring.paper.jpa.datasource.password", ""));
        hikari.setMaximumPoolSize(config.integer("spring.paper.jpa.datasource.maximum-pool-size", 10));
        hikari.setPoolName("SpringPaper");
        return new HikariDataSource(hikari);
    }
}
