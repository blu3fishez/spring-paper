# 기본 빈과 전용 빈

기본 빈은 실제 typed @Bean 정의로 노출하며 @Lazy로 필요할 때 생성한다.
소비자가 정상 기본 후보를 제공하면 공급자 정의를 제거한다. 후보 존재 판단은 Spring의
`isAutowireCandidate(DependencyDescriptor)`를 사용한다. 타입 이름만 세거나 빈을 미리 만들어 선택하지 않는다.

대부분의 기본 빈은 `@Fallback`도 사용하지만 일반 scheduler와 CacheManager에는 붙이지 않는다.
Spring 7.0.10의 TaskSchedulerRouter·CacheAspectSupport는 주입 descriptor 대신 직접 타입으로 조회한다.
이 경로는 유일한 non-fallback을 defaultCandidate 판정보다 먼저 선택하므로, 라이브러리 기본에
Fallback을 붙이면 `defaultCandidate=false`인 소비자 전용 자원으로 예약·캐시 업무가 흘러간다.
이 두 기본은 후보가 있으면 정의를 제거하는 동일한 절차만으로 교체를 처리한다.

| 소비자 제공 | 일반 주입 결과 | 라이브러리 기본 생성 |
|---|---|---|
| 없음 | 라이브러리 기본 | 해당 기능을 실제 사용할 때 |
| 일반 기본 | 소비자 기본 | 안 함 |
| defaultCandidate=false 전용만 | 라이브러리 기본, qualifier 주입은 전용 | 실제 사용할 때 |
| 일반 기본 + 전용 | 소비자 기본 | 안 함 |
| 일반 후보 복수 + @Primary | Spring이 Primary 선택 | 안 함 |
| 소비자 @Fallback만 | 소비자 Fallback | 안 함 |
| 일반 기본 + 소비자 @Fallback | Spring이 일반 기본 선택 | 안 함 |
| FactoryBean 제품 | 예측 가능한 제품 타입으로 같은 규칙 | 기본 안 함, 제품은 정상 생성 시점에만 생성 |

표의 일반 주입은 Spring의 dependency descriptor 경로다. 소비자가 직접 `@Fallback` 기본과
non-fallback 전용 자원을 함께 제공하면 Spring의 위 직접 조회 순서는 그대로 적용된다.
그 조합에서 일반 `@Scheduled`의 대상을 고정하려면 `scheduler` 이름 또는 `SchedulingConfigurer`를,
`@Cacheable`의 대상을 고정하려면 `CachingConfigurer`의 manager/resolver 또는 `cacheManager` 이름을
명시한다. 라이브러리가 소비자의 Primary/Fallback metadata를 바꾸거나 Spring 선택을 우회하지 않는다.

```java
@Bean(defaultCandidate = false)
JdbcOperations maintenanceJdbc(DataSource source) {
    var jdbc = new JdbcTemplate(source);
    jdbc.setQueryTimeout(3);
    return jdbc;
}
// 일반 JdbcClient/JdbcOperations는 라이브러리 기본을 계속 사용한다.
// 전용 소비자는 @Qualifier("maintenanceJdbc")로 선택한다.
```

JDBC의 JdbcOperations·NamedParameterJdbcOperations·JdbcClient에 같은 규칙을 적용했다.
JdbcClient만 제공한 애플리케이션은 쓰지 않을 기본 JdbcTemplate 생성 때문에 DataSource를 강제하지 않는다.
외부 DataSource와 FactoryBean 제품을 새로 복제하거나 JDBC 구성에서 닫지 않는다.
Spring에 제공한 @Bean의 destroyMethod는 그 제공자가 소유하며 외부 수동 singleton은 외부에서 닫는다.
트랜잭션 매니저는 JDBC 구성이 임의로 생성하지 않는다. JPA와 함께 사용하면 기존 JPA 매니저를 공유한다.

같은 결함이 재현된 Cache·Security 기본 및 JPA DataSource/transaction-manager 탐색에도 적용했다.
대체된 JPA 기본 풀/EMF는 생성하지 않는다. 활성 JPA 구성의 EMF는 Spring의 LoadTimeWeaverAware
bootstrap에서 초기화된다. @Lazy만으로 이 초기화를 지연한다고 보장하지 않는다. 이미 제공한 EMF가 있으면 별도 공개 DataSource나
spring.paper.jpa.datasource.url을 요구하지 않는다. JPA는 다중 EMF/매니저의 명시적 이름 선택이라는
기존 계약을 유지한다. `spring.paper.jpa.entity-manager-factory-bean`·`transaction-manager-bean`으로 선택한다.
DataSource의 일반 주입 선택은 Spring의 Primary/Fallback 규칙을 따른다.

JPA의 명시적인 이름 설정은 alias와 `defaultCandidate=false` 전용 자원도 선택한다. 제품 타입이
예측 가능한 FactoryBean을 이 선택 때문에 조기 생성하지 않는다. 없는 이름과 예측된 잘못된 타입은
기본 풀 생성 전에 거절한다. 명시적으로 이름을 고른 FactoryBean의 제품 타입이 아직 없으면 조기
생성하지 않고 실제 생성 때 Spring의 typed lookup으로 검증한다. `entityManagerFactory`·`transactionManager`가 전용 자원으로 이미 점유되어 일반 기본을
연결할 수 없으면 덮어쓰지 않고 시작을 거절한다. 그 자원이 JPA의 선택 대상이면 해당 이름 설정으로
명시하고, 별도 전용 자원이면 빈 이름을 바꾼다. 이미 같은 자원을 가리키는 alias는 그대로 재사용한다.

부모 컨텍스트의 명시 자원과 자식에서 부모로 연결한 alias도 같은 규칙을 따른다. Spring 7.0.10은
상속된 `getType(name, false)`를 부모의 일반 `getType(name)`에 위임하므로, 공개 hierarchy/alias metadata로
실제 소유 factory를 먼저 찾아 초기화 없는 타입 검사를 한다. 소유 정보를 공개하지 않는 일반 부모
BeanFactory는 타입 검사를 실제 typed lookup까지 미룬다. 이 과정에서 다른 후보 선택 알고리즘을 만들지 않는다.

worker와 Security timer는 **역할 qualifier**로 선택하며 유지보수용 기본 scheduler와 섞지 않는다.
전용 worker도 그 역할에는 정상 후보가 된다. 설정과 소유권은 [실행 계약](execution-contracts.md)을 참고한다.

`DefaultBeans`는 config/internal에 둔 모듈 사이 구현 지원이다. consumer용 확장 지점이 아니다.
FactoryBean 제품 타입을 예측할 수 없으면 조기 생성해 알아내지 않는다. FactoryBean<T>와 getObjectType의
정확한 타입 정보를 제공해야 한다. 다중 일반 후보를 Primary/Qualifier 없이 임의 선택하지 않는다.

IDE와 런타임은 동일한 @Bean·@Import·ComponentScan/타입 정보를 사용한다. IDE 경고를 숨기는 가짜 빈은 없다.
JPA의 programmatic 인프라, 조건부 연결 어댑터, ServiceLoader 결과와 후처리로 제거한 모든 정의까지
IDE가 정적으로 똑같이 평가한다고 보장하지 않는다. 실제 빈 선택은 Spring 컨텍스트 회귀 테스트로 검증한다.

확인한 소스는 Spring 7.0.10의 DefaultListableBeanFactory, QualifierAnnotationAutowireCandidateResolver,
DependencyDescriptor, BeanDefinition/FactoryBean 타입 예측이다.
[Bean.defaultCandidate](https://docs.spring.io/spring-framework/docs/7.0.10/javadoc-api/org/springframework/context/annotation/Bean.html#defaultCandidate())와
[Primary/Fallback](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/autowired-primary.html)의 표준 규칙을 사용한다.
