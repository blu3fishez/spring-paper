# 설정과 확장 계약

## 로딩

런타임은 진입점의 `configuration`(기본 application.yml) 리소스와 플러그인 데이터 디렉터리의 같은 파일을 합친다. 외부 파일이 JAR 기본값을 덮어쓴다. Spring 시스템 프로퍼티·환경 변수는 YAML보다 우선하며 `${ENV:default}` placeholder를 해석한다. 환경 변수는 Spring의 표준 이름 변환을 사용한다(예: SPRING_PAPER_JPA_ENABLED).

`spring.profiles.active`에 지정한 프로필 순서대로 application-{profile}.yml도 합친다. 각 프로필은 JAR → 외부 파일 순서이며 뒤 프로필이 앞 프로필을 덮어쓴다. 비밀값을 JAR에 포함하지 않는다.

configuration 리소스가 .yaml이면 프로필 파일도 .yaml 확장자를 유지한다. 프로필 파일 안에서 spring.profiles.active를 다시 정의하면 시작 오류로 알린다.

Gradle 개발 설정은 `springPaper.configurationFile`의 YAML(기본 src/main/resources/application.yml)에서 읽는다. 런타임 프로필이나 서버의 외부 설정 파일을 Gradle 빌드 시 자동 실행·로딩하지 않는다. 개발 서버와 배포 설정은 서로 다른 실행 단계다.

명령행에서는 `-PspringPaper.configuration=application-local.yml`로 로컬 전용 전체 설정 파일을 선택할 수 있다. 이 파일은 .gitignore에 포함된다. 기본 예제의 EULA false는 그대로 두고 명시적으로 수락한 로컬 설정으로 개발 서버를 실행할 수 있다.

빌드에 사용되는 Minecraft·개발 서버 설정과 진입점/명령의 문자열 메타데이터에도 `${ENV:default}` 및 YAML 프로퍼티 참조를 사용할 수 있다. 빌드가 읽지 않는 JPA 비밀값 등은 빌드 시 요구하지 않는다. 명령 이름의 build/runtime 설정은 같아야 한다. plugin.yml은 빌드 시 고정되므로 실행 중 환경 변수로 명령 레이블을 바꾸면 시작 검증에서 누락으로 알린다.

## Paper·개발 도구

| 키 (`spring.paper.` 다음) | 의미 / 기본값 |
|---|---|
| minecraft.version | 개발 서버 대상 Minecraft 버전, 필수 |
| minecraft.api-version | plugin.yml API 선언; 생략 시 version |
| minecraft.api-coordinate | Paper API Maven artifact 버전; 생략 시 `{version}.build.+`. 재현 가능한 빌드에는 명시적으로 고정 |
| minecraft.build | run-paper 서버 빌드 번호. 생략하면 run-paper의 최신 빌드 선택을 사용하므로 안정 빌드를 확인해 고정 권장 |
| minecraft.java-version | 소비 플러그인 컴파일·개발 서버 JDK, 기본 25 |
| development.directory | 개발 서버 작업 디렉터리, 기본 run |
| development.accept-eula | 사용자가 수락한 EULA 여부, 기본 false |
| development.jvm-args | Java 실행 인자 목록 |
| development.server-properties | server.properties에 합칠 키와 값. 다른 기존 키는 유지 |
| lifecycle.shutdown-timeout-seconds | 기본 worker executor 종료 대기, 기본 5초. 사용자 flush의 별도 대기 정책을 대신하지 않음 |

코어 워커는 사용자 stop 이후, DB·캐시 등 컨텍스트 인프라를 닫기 전에 새 제출을 차단하고 실행 중 작업을 기다린다. 컨텍스트 destruction은 시작 실패 정리를 위한 idempotent fallback으로도 close를 호출할 수 있다. 대기 시간이 지나면 워커를 interrupt하고 경고를 남기며, 실제 종료를 무한 대기하거나 저장 완료를 보장하지 않는다. 사용자 flush·멱등 재시도·내구성 정책은 각 기능이 유지한다.

현재 실행 검증 대상은 Paper 26.3/Java 25다. 버전 설정 가능성과 모든 과거/미래 Paper 버전의 호환성은 다르다. 다른 버전은 적절한 API coordinate/JDK와 실제 기동을 검증해야 한다. Folia의 region scheduler는 지원하지 않는다.

## PDC

`spring-paper-data-pdc`를 의존성에 추가하고 활성 구성에서 저장소 범위를 선택한다.
일반 component 스캔은 인터페이스 저장소를 만들지 않는다. 역할별 구성에 아래 선언을 두면
비활성 역할의 저장소도 정의되지 않는다.

```java
@Configuration(proxyBeanMethods = false)
@EnablePaperPdcRepositories(basePackageClasses = BrokerTags.class)
class BrokerConfiguration {}

@PaperPdcRepository
public interface BrokerTags extends PdcRepository<Entity, BrokerTags.State> {
    @PdcDocument(marker = "islandId")
    record State(
        @PdcField(key = "black_market/broker_island") long islandId,
        @PdcField(key = "black_market/broker_owner") UUID ownerId
    ) {}
}
```

`read`는 `PdcReadResult`를 반환한다. `PRESENT`는 값, `ABSENT`는 매핑한 키가 모두 없음,
`INVALID`는 부분 누락·잘못된 저장 타입·변환/record 검증 실패다. 문제의 키와 종류도 제공한다.
읽기는 복구·삭제·이관을 수행하지 않는다. 소비자는 `.value()`의 Optional이나 문제 목록으로
자기 기능의 기존 실패 정책을 적용한다.

`exists`는 문서의 marker에 지정한 record component의 **키 존재**를 검사한다.
marker를 생략하면 매핑한 키 하나 이상이 있는지 확인한다. 타입·내용의 정상 여부는 read 결과로 판단한다.
엔티티 UUID처럼 holder에서 계산하는 정보는 소비자 매핑이 얻으며 별도 저장 키를 만들지 않는다.

키의 기본 namespace는 플러그인 이름이고 `namespace:key`도 명시할 수 있다.
키 문자열은 반드시 선언한다. Java 필드 이름 변경으로 저장 키가 바뀌지 않으며 버전 키를 자동 추가하지 않는다.
기본 매핑은 native 숫자·문자열·boolean·primitive 배열이고, UUID는 **STRING**,
enum은 **STRING/name()**을 사용한다. 기존 형식이 다르면 Paper의 `PersistentDataType` 구현을
Spring 빈으로 제공하고 `@PdcField(type = MyType.class)`로 선택한다. 이 SPI를 중복 정의하지 않는다.
`required = false`는 nullable reference component에만 사용하며 null을 쓰면 이전 키를 지운다.

holder는 `Entity`, `ItemStack`, `PersistentDataContainer`다. ItemStack의 읽기 뷰는 수정하지 않고
Paper의 editPersistentDataContainer로 변경을 반영한다. container 저장소는 ItemMeta 등
소비자가 이미 획득한 mutable container에도 사용할 수 있다. 이 경우 외부 객체의 최종 반영은 소비자 책임이다.
빈으로 선언하지 않는 경우 `PdcRepositoryFactory.entity/item/container(model)`도 사용할 수 있다.
`spring.paper.pdc.enabled: true`는 이 factory의 자동 구성을 켜며, Enable 어노테이션으로도 명시적으로 구성할 수 있다.

모든 holder 접근은 Paper 메인 스레드에서 동기적으로 실행한다. 작업자 호출은 holder를 만지기 전에 거절한다.
쓰기 전에 모든 필드를 별도 container에서 변환·검증해 변환 실패로 인한 부분 쓰기를 방지한다.
이것이 Paper의 디스크 저장·원자적 트랜잭션·DB와의 동시 commit을 보장하지는 않는다.
remove는 문서가 소유한 키만 삭제한다. 스키마 이관·서명·아이템 소유권·게임 규칙은 소비자가 유지한다.
기존 저장 키와 타입을 바꾸는 이관을 암묵적으로 실행하지 않는다.

## JPA

```yaml
spring:
  paper:
    jpa:
      enabled: true
      repositories: com.example.mail.repository
      entities: com.example.mail.domain
      datasource:
        url: '${DATABASE_URL}'
        username: '${DATABASE_USER}'
        password: '${DATABASE_PASSWORD}'
        maximum-pool-size: 10
      properties:
        hibernate.hbm2ddl.auto: validate
```

repositories/entities는 제한된 패키지 목록 또는 쉼표 구분 문자열이다. Hikari/Hibernate 기본 자원은 Spring이 소유하고 종료한다. DataSource·EntityManagerFactory·PlatformTransactionManager가 이미 하나 있으면 재사용한다. 여러 개면 아래 키로 명시한다.

- `spring.paper.jpa.data-source-bean`
- `spring.paper.jpa.entity-manager-factory-bean`
- `spring.paper.jpa.transaction-manager-bean`

새 DataSource를 만들 때만 datasource 설정이 필요하다. JDBC 드라이버는 소비 플러그인이 제공한다. Hibernate 엔티티를 게임 세션에 공유하지 않고 트랜잭션 안에서 완료된 값을 반환한다. 소유권·revision·조건부 갱신·영향 행 수·정산 원자성은 저장소의 명시적 계약으로 유지한다.

JPA/JDBC/Jdbi가 같은 DB를 사용한다는 사실만으로 같은 트랜잭션이 되지는 않는다. Jdbi 자동 이관·Handle 계약 변경·분산 DB/Redis 원자성은 이 라이브러리가 암묵적으로 수행하지 않는다. 기존 트랜잭션 매니저를 제공하면 스레드 검사와 트랜잭션 참여 정책도 소비자가 구성한다.

## Cache

```yaml
spring:
  paper:
    cache:
      enabled: true
      names: [guide-progress, item-catalog]
      spec: 'maximumSize=1000,expireAfterWrite=5m'
```

CacheManager를 직접 제공하면 재사용한다. 없으면 명시한 names/spec으로 Caffeine을 만들고 TransactionAwareCacheManagerProxy로 감싼다. commit 전 put/evict는 지연하고 rollback된 변경은 캐시에 적용하지 않는다. putIfAbsent 같은 즉시 실행 연산은 별도 Spring 계약을 따른다.

Redis CacheManager도 소비자 설정에서 제공할 수 있으나 Redis 자동 연결·서버 간 무효화·durable outbox는 현재 제공하지 않는다. TTL은 권위 있는 인벤토리 상태의 소유권이나 중복 지급 방지 규칙을 대체하지 않는다.

## Security

`spring.paper.security.enabled: true`로 메서드 인가와 확장형 인증 기반을 켠다. 선택적으로 `spring.paper.security.denied-message`를 설정한다. 전체 SPI와 소비 예제는 [Spring Paper Security](security.md)에 있다.

플랫폼 메서드 인가 모드의 기본 resolver는 동기 호출의 true effective permission을 authority로 쓰고 OP에 ROLE_OPERATOR, 콘솔에 ROLE_CONSOLE을 추가한다. 이 PaperPrincipal은 플랫폼이 식별한 호출자이며 별도 계정 검증을 마쳤다는 증거가 아니다.

접속 모드는 `spring.paper.security.connection.enabled: true`로 켠다(기본 false). 기본 Paper 어댑터와 세션 resolver를 연결하며, PENDING 상태의 부분 인증은 메서드 인가에 노출하지 않는다. 검증을 수행하는 소비자 필터/Provider가 없으면 접속은 거절된다. 기존 접속자를 자동 인증하지 않고 재접속을 요구하므로, 자체 복원 정책이 있으면 어댑터를 끄고 소비자 구현을 연결한다.

| 키 (`spring.paper.security.` 다음) | 기본값 / 의미 |
|---|---|
| pending-timeout-seconds | 120, 최초 대기 만료 시간. 재시도해도 늘어나지 않음 |
| operation-timeout-seconds | 10, 저장소·필터·결과 처리를 포함한 단일 인증 작업 제한 |
| shutdown-timeout-seconds | 5, 종료 정리 확인 제한 |
| connection.enabled | false, Paper 기본 접속 어댑터 활성화 |
| connection.admission-timeout-seconds | 10, Paper 비동기 입장 이벤트의 최대 대기 |
| connection.refresh-period-ticks | 20, 권위 저장소 재조회 주기 |
| connection.snapshot-ttl-seconds | 5, 재조회가 멈췄을 때 기존 인증 snapshot을 쓸 수 있는 최대 시간 |

비동기 이벤트의 기본 플랫폼 resolver는 Player 권한을 읽지 않는다. 세션 resolver는 변경 불가능한 접속 snapshot만 사용하므로 비동기 호출도 처리할 수 있다. 소비자 `PaperAuthenticationResolver`는 I/O 없이 현재 호출자의 인증을 반환한다. 작업자에 전달한 문맥은 이후 세션/권한 변경을 자동 재검증하지 않는다.

## 라이브러리 확장

진입점의 `initializers`는 Spring 표준 `ApplicationContextInitializer<ConfigurableApplicationContext>` 목록이다. Paper 메인 스레드에서 기존 host 설정을 읽거나 immutable singleton·PropertySource를 넣고, 이후 작업자 refresh에 전달한다. initializer 자체를 Spring component로 재생성하거나 별도 DI 컨테이너로 만들지 않는다.

`@PaperEventListener`는 타입과 `@Bean` 메서드에서 사용할 수 있다. 기존 소유 객체가 만든 리스너를 factory로 노출하면 캡처한 세션과 callback을 공유한다. 입력 등록/해제는 Spring Paper가 소유하고, 소유 객체가 내부 작업을 닫는다면 factory에 `destroyMethod=""`를 지정한다. 실제 factory method와 타입의 어노테이션 조회 및 순서는 Spring BeanFactory의 표준 계약을 사용한다.

`@PaperCommandExecutor`는 반복 선언할 수 있다. 레이블마다 다른 usage·permission 메타데이터를 생성하며 실행 객체는 하나다. 진입점 `permissions`는 `@PaperPermission(name=..., description=..., defaultValue=...)` 배열로 명령 밖의 권한도 선언한다. 같은 권한의 충돌하는 기본값은 빌드 오류다.

시작/종료 훅의 `phaseString`은 정수 또는 Environment placeholder를 받는다. 명시적인 종료 phase는 높은 값부터 적용하고 같은 값은 실제 시작 순서를 뒤집는다. 기본 종료 phase는 시작 phase를 상속한다. 시작하지 않은 훅을 종료 시 새로 만들거나 실행하지 않는다. 소비자의 `onApplicationStartupFailure`는 부분 시작 정리 이후 호출된다.

SpringPaperModule SPI는 코어가 호출하고 선택 모듈이 구현한다. META-INF/services 등록과 enabledProperty/configurationClass를 제공하면 활성화된 모듈 구성만 컨텍스트에 들어온다. provider 생성자는 I/O와 자원 생성을 하지 않는다.

PaperInvocationInterceptor SPI는 코어의 이벤트·명령·탭 완성 경로를 감싼다. Spring orderedStream 순서를 따른다. 실제 빈은 생성자 주입하며, 어노테이션을 읽기 위해 프록시를 벗겨 원본을 호출하지 않는다.

컨텍스트 준비, 사용자 start/stop, 입력 호출과 PaperTasks 작업은 플러그인의 문맥 클래스 로더를 사용하고 이전 로더를 복원한다. 다른 플러그인의 컨텍스트나 전역 정적 컨텍스트를 재사용하지 않는다.

빈과 프록시의 클래스 정의에는 Paper의 원래 플러그인 클래스 로더를 쓴다. 리소스 조회에만 PaperResourceLoader를 연결해 플러그인 → 서버 순서로 메타데이터를 찾는다. 실제 Paper의 로더는 서버 클래스를 로딩할 수 있어도 같은 클래스의 리소스를 노출하지 않아 Spring의 인터페이스 분석이 실패했다. ResourceLoader 보완으로 처리하며 Paper API를 플러그인에 복제하지 않는다.

Paper 입력 어댑터와 사용자 lifecycle 훅은 eager singleton이어야 한다. lazy/prototype이면 조용히 누락시키지 않고 시작 오류로 알린다. 비활성 서버 역할·인증 모드는 조건부 설정에서 제외한다. 일반 서비스와 지연 자원은 해당 조건 안에서 기존 지연 초기화를 유지할 수 있다. 코어가 제공하는 paperPlugin/paperServer/paperLogger/paperConfiguration/paperServerInfo/paperThreadAccess 이름은 재정의하지 않는다.

## Spring 내부 구현에서 확인한 확장 계약

아래 링크는 사용 중인 Framework 7.0.10과 Security 7.1.2 소스다. 컨테이너·프록시·트랜잭션·캐시를 별도로 재구현하지 않고 공개 확장 지점에 Paper 책임을 연결한다.

| 확인한 Spring 구현 | Spring Paper에서 사용하는 계약 |
|---|---|
| [GenericApplicationContext](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-context/src/main/java/org/springframework/context/support/GenericApplicationContext.java), [ConfigurationClassPostProcessor](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-context/src/main/java/org/springframework/context/annotation/ConfigurationClassPostProcessor.java) | 컨텍스트의 클래스 로더와 ResourceLoader를 분리한다. 구성 파서는 제공한 ResourceLoader로 메타데이터를 읽는다. 리소스 탐색은 PaperResourceLoader만 소유한다. |
| [InitDestroyAnnotationBeanPostProcessor](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-beans/src/main/java/org/springframework/beans/factory/annotation/InitDestroyAnnotationBeanPostProcessor.java), [MergedBeanDefinitionPostProcessor](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-beans/src/main/java/org/springframework/beans/factory/support/MergedBeanDefinitionPostProcessor.java), [DisposableBeanAdapter](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-beans/src/main/java/org/springframework/beans/factory/support/DisposableBeanAdapter.java) | PaperBeanCatalog는 merged metadata에서 후보와 외부 종료 소유권만 기록한다. 기반 definition 속성을 수정하거나 Paper 입력을 등록하지 않는다. PaperStop이 소유한 종료는 Spring이 다시 호출하지 않는다. |
| [EventListenerMethodProcessor](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-context/src/main/java/org/springframework/context/event/EventListenerMethodProcessor.java) | 동일한 AopUtils.selectInvocableMethod 계약으로 관리 빈의 프록시를 호출한다. 메타데이터를 발견한 뒤 실제 입력을 여는 책임은 PaperListenerRegistrar/PaperCommandRegistrar가 맡는다. |
| [DefaultListableBeanFactory](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultListableBeanFactory.java), [ApplicationListenerMethodAdapter](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-context/src/main/java/org/springframework/context/event/ApplicationListenerMethodAdapter.java) | findAnnotationOnBean과 getOrder로 factory method metadata를 존중한다. package-private 어댑터의 public 메서드도 프록시에서 선택한 뒤 makeAccessible하며 원본 target으로 advice를 우회하지 않는다. |
| [ClassPathScanningCandidateComponentProvider](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-context/src/main/java/org/springframework/context/annotation/ClassPathScanningCandidateComponentProvider.java), [ImportBeanDefinitionRegistrar](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-context/src/main/java/org/springframework/context/annotation/ImportBeanDefinitionRegistrar.java), [FactoryBeanRegistrySupport](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-beans/src/main/java/org/springframework/beans/factory/support/FactoryBeanRegistrySupport.java) | PDC의 제한된 인터페이스 탐색·조건 평가·타입 발견·singleton 제품 캐시·post-processing은 Spring에 맡긴다. 실제 holder 접근과 record 매핑만 PDC 모듈이 소유한다. |
| [AbstractApplicationContext](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java) | refresh 실패의 singleton 정리와 컨텍스트 destruction은 Spring이 소유한다. 메인 스레드 사용자 훅과 입력 해제·워커 drain 순서만 SpringPaperApplication이 그 바깥에서 조정한다. |
| [AbstractPlatformTransactionManager](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-tx/src/main/java/org/springframework/transaction/support/AbstractPlatformTransactionManager.java) | beforeBegin이 doBegin보다 먼저 실행되는 공식 listener로 메인 스레드 DB 작업을 차단한다. 트랜잭션 구현은 JpaTransactionManager에 위임한다. |
| [TransactionAwareCacheDecorator](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-context-support/src/main/java/org/springframework/cache/transaction/TransactionAwareCacheDecorator.java) | put/evict/clear의 afterCommit 반영을 Spring에 위임한다. 자체 트랜잭션 캐시 동기화기를 만들지 않는다. |
| [AuthorizationManagerBeforeMethodInterceptor](https://github.com/spring-projects/spring-security/blob/7.1.2/core/src/main/java/org/springframework/security/authorization/method/AuthorizationManagerBeforeMethodInterceptor.java), [DelegatingSecurityContextRunnable](https://github.com/spring-projects/spring-security/blob/7.1.2/core/src/main/java/org/springframework/security/concurrent/DelegatingSecurityContextRunnable.java) | 메서드 인가는 Spring Security가 처리한다. Paper 어댑터는 호출별 불변 사용자 정보와 finally 문맥 복원만 담당하며 작업자 문맥 전달은 Spring의 명시적 wrapper에 맡긴다. |

실제 장애·회귀·실행 승인 대기 이력과 관련 소스 위치는 [검증 기록](verification.md#막혔던-지점과-코드-주석)에서 확인할 수 있다.

## 라이브러리 조합

| 라이브러리 | 고정 검증 버전 |
|---|---|
| Java / Paper API | 25 / 26.3.build.244-stable |
| Spring Framework | 7.0.10 |
| Spring Data BOM / JPA | 2026.0.2 / BOM에서 선택 |
| Spring Security | 7.1.2 |
| Hibernate ORM / Jakarta Persistence | 7.4.13.Final / 3.2.0 |
| HikariCP / SnakeYAML | 7.0.2 / 2.6 |
| Caffeine | 3.2.4 |
| Gradle / run-paper / Shadow | 9.6.1 / 3.0.2 / 9.6.0 |
| Lombok (compileOnly / annotationProcessor) | 1.18.48 |

서버 API와 SLF4J 구현은 Shadow JAR에 포함하지 않는다. Spring/Hibernate 및 SPI 서비스 메타데이터는 소비자 JAR에 포함·병합한다. 호환되지 않는 Spring 버전을 여러 플러그인이 동시에 사용하는 환경이나 별도 relocation 정책은 현재 검증 범위에 포함되지 않는다.
