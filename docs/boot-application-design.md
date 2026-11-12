# 단일 Paper 애플리케이션과 Spring Boot 재사용 설계안

2026-11-12 사양 채택 · 2026-11-13 제품 구현·검증 기록

## 1. 확정 범위와 아직 승인하지 않은 사항

목표는 하나의 큰 Paper 플러그인 안에서 업무 모듈을 조립하는 Minecraft 서버 애플리케이션이다.
사용자는 실제 Spring Boot 재사용을 허용하되 **웹 관련 부산물·웹 서버·웹 starter·Servlet/MVC/WebFlux 관련 모듈을
일체 사용하지 않는 조건**을 확정했다. Tomcat 등 웹 컨테이너도 포함한다. 웹 금지는 아래 모든 제안의 확정 제약이다.
사용자가 이 문서의 세 가지 1차 추천안을 모두 명시 채택하여, **Boot 4.1.2 core+autoconfigure의 제품 사용과 1차 구현**을 승인했다.
현재 Java 25, Paper 26.3 build 244,
Spring 7.0.10, Data 2026.0.2, Security 7.1.2, Hibernate 7.4.13, Velocity 4.2.1-SNAPSHOT을 바꾸지 않았다.

추가 확정 요구는 Paper 앱을 기존 Velocity의 backend로 연결하고, 별도 Velocity 프로젝트에서
`@SpringVelocityApplication`으로 plugin JAR과 proxy 설정 준비·실행 task를 제공하는 것이다.
Paper 산출물은 native Paper가 로드하는 플러그인이다. executable `bootJar`나 자체 Minecraft 배포판은 전제하지 않는다.
이 문서의 세 가지 1차 추천안에 속한 새 이름·키·기본값·우선순위·실행 정책은 **명시 채택한 사양**이다.
단순한 “진행”을 미정 사항의 선택으로 해석한 것이 아니며, 사용자가 별도로 세 추천안을 모두 채택했다.
현재 승인 범위에 운영 파일 적용·host 프로세스 실행·원격 proxy 등록·secret 생성/회전은 포함하지 않는다.

## 2. 전환 전에 확인한 문제와 설계 근거

이 절은 구현 전 조사 기록이다. 빌드 설정 결합과 ordinary root 부재는 1차 구현에서 다루며,
현재 제품 동작과 검증 결과는 §11을 따른다. env-only vendor map 복원과 startup main 대기 위험을
모든 경우에 해결한 것으로 해석하지 않는다.

| 판단 | 증거와 소비자 영향 |
|---|---|
| P2: 빌드와 런타임 설정 결합 | [Gradle apply](../spring-paper-gradle-plugin/src/main/java/org/blu3fishez/springpaper/gradle/SpringPaperPlugin.java#L62)가 모든 task의 구성 단계에서 YAML과 Minecraft 버전을 요구한다. 임시 소비자 `help`도 파일 없음/빈 파일에서 실패했다. 버전 관련 다섯 값이 모두 필수인 것은 아니다. |
| P2: 환경변수로 추가한 map 항목 누락 | [children](../spring-paper-config/src/main/java/org/blu3fishez/springpaper/config/PaperConfiguration.java#L39)은 dotted 이름만 열거한다. env-only Hibernate 항목은 직접 조회되지만 [JPA 전달 map](../spring-paper-data-jpa/src/main/java/org/blu3fishez/springpaper/jpa/JpaConfiguration.java#L68)에서는 사라진다. |
| P2: startup에서 main 대기 시 교착 가능 | [refresh worker](../spring-paper-core/src/main/java/org/blu3fishez/springpaper/bootstrap/SpringPaperApplication.java#L106)를 Paper main이 `get()`으로 기다린다. `@Bean`/`@PostConstruct`가 worker에서 [tasks.main(...)](../spring-paper-core/src/main/java/org/blu3fishez/springpaper/task/PaperTasks.java#L51)의 완료를 `join()`하면 main queue와 순환 대기할 수 있다. source에서 확인한 위험이며 별도 native 재현은 하지 않았다. |
| 설계: 앱 루트 관례 부족 | [진입점](../spring-paper-core/src/main/java/org/blu3fishez/springpaper/annotation/SpringPaperPluginApplication.java#L30)의 구성·스캔은 기본 빈 배열이다. 일반 `@Service`를 추가하려면 별도 구성·스캔을 먼저 연결해야 한다. |
| 설계: 기능별 조립 규칙 분산 | JPA entities/repositories, PDC Enable, Cache names/spec, Module SPI/직접 import의 의미를 각각 배워야 한다. [JPA repositories](../spring-paper-data-jpa/src/main/java/org/blu3fishez/springpaper/jpa/JpaRepositoryScanning.java#L10)는 표준 Enable로 대체할 수 있고, 사용자 EMF/CacheManager가 있으면 해당 기본 설정은 필요 없다. |

모든 모듈이 무분별하게 결합됐다고 단정하지 않는다. 관리형 실행·부분 시작 정리·PDC 매핑은 소비자가
반복 구현할 복잡성을 감추는 깊은 모듈이다. 우선 줄일 대상은 애플리케이션 조립에 필요한 interface와 중복 기본 구성이다.

## 3. 소비자가 작성할 코드: 채택 사양

기존 호환 모드는 native 진입점, 별도 `@Configuration`, `@ComponentScan`, IDE용 core import를 연결한다.
새 모드는 ordinary Java root 하나가 Boot의 scan·auto-configuration·typed properties 탐색을 연결한다.
다음 이름과 ordinary root 관례는 1차 채택 사양이다. 구현 상태와 실제 검증 범위는 §11에 기록한다.

```java
@SpringPaperApplication(name = "GameServer")
public class GameServerApplication {}

// GameServerApplication의 패키지 아래에 추가하면 발견된다.
@Service
public class GreetingService {
    public GreetingService(GameSettings settings) { /* 업무 */ }
}

@ConfigurationProperties("game")
public record GameSettings(@DefaultValue("Welcome") String greeting) {}
```

기본 스캔은 ordinary root의 패키지, 범위 변경은 Boot의 type-safe package marker로 제공한다.
Paper가 만든 `JavaPlugin`을 스캔 대상으로 만들지 않는다. 선택 역할은 명시적 구성·조건으로 분리한다.
root 전체 스캔이 모든 선택 기능의 하위 stereotype까지 조건부로 묶어 준다고 약속하지 않는다.

Velocity 전용 프로젝트도 ordinary root를 사용한다.

```java
@SpringVelocityApplication(id = "gateway", name = "Gateway")
public class GatewayApplication {}
```

새 Paper 어노테이션은 `annotation.SpringPaperApplication`, 기존 helper는 `bootstrap.SpringPaperApplication`이다.
Velocity도 새 `velocity.annotation.SpringVelocityApplication`과 기존 `velocity.bootstrap.SpringVelocityApplication`
helper의 FQCN을 구별한다(모두 `org.blu3fishez.springpaper` 아래). native 공통 bridge가 **자기 JAR의 root metadata**를 읽는 1차 채택 사양이다.
Velocity native loader에서 같은 FQCN의 bridge를 가진 소비자 두 JAR이 독립적으로 생성되는 proof가 있다.
Paper bridge·descriptor와 최종 양쪽 산출물은 §11의 제품 검증에서 별도로 다룬다. descriptor 생성의 source of truth는 하나다.

## 4. 빌드 target·descriptor·개발 실행의 분리: 채택 사양

```groovy
// Paper 프로젝트. 값은 현재 검증 target을 표시한 예시다.
plugins { id 'org.blu3fishez.spring-paper' }
version = '0.1.0-SNAPSHOT'
springPaper {
    paper {
        apiCoordinate = 'io.papermc.paper:paper-api:26.3.build.244-stable'
        apiVersion = '26.3'
    }
    // assemble에는 필요하지 않은 개발 실행 설정
    development {
        minecraftVersion = '26.3'
        build = 244
    }
}
```

`help/tasks`는 target이나 런타임 YAML을 읽지 않는다. `assemble`은 합의한 compile target과 공개 descriptor
메타데이터만 요구하고, DB·인증·proxy 비밀값이나 개발 서버 설정을 평가하지 않는다.
1차 Paper 사양은 `apiCoordinate`(full Gradle G:A:V)와 `apiVersion` 두 명시 필드다.
좌표에서 API 선언을 추론하거나 단일 target object로 묶는 기능은 후속 범위다.

```groovy
// 별도 Velocity 프로젝트. API와 실행 대상을 각각 명시한다.
plugins { id 'org.blu3fishez.spring-velocity' }
version = '0.1.0-SNAPSHOT'
springVelocity {
    apiCoordinate = 'com.velocitypowered:velocity-api:4.2.1-SNAPSHOT'
    development {
        version = '4.2.1-SNAPSHOT'
        buildNumber = 43
    }
}
```

양쪽 `apiCoordinate`는 같은 full G:A:V 의미로 사용한다. Velocity 실행 설정은 기존 run-task **3.0.2**의
`velocityVersion('4.2.1-SNAPSHOT')`와 상속한 `build(43)`에 대응한다.
[RunVelocity](https://github.com/jpenilla/run-task/blob/v3.0.2/plugin/src/main/kotlin/xyz/jpenilla/runvelocity/task/RunVelocity.kt#L47)와
[AbstractRun](https://github.com/jpenilla/run-task/blob/v3.0.2/plugin/src/main/kotlin/xyz/jpenilla/runtask/task/AbstractRun.kt#L176)의 지원을 확인했으며,
명시 version+build를 run 때 요구해 latest 선택을 하지 않는다. 해당 원격 build 다운로드는 이번에 실증하지 않았다.
조회 task에서는 실행 대상을 resolve하지 않고, assemble에서는 compile coordinate만 resolve한다. JDK는 현재 고정한 25를 유지한다.
한 Gradle 프로젝트에는 host 하나를 두고, `:game`/`:gateway`처럼 하위 프로젝트로 나눈다.
Paper의 기존 `runServer`는 유지하고 Velocity는 `runVelocity`; 각각 자기 JAR·작업 디렉터리를 사용한다.
Paper 앱의 proxy 연결 기능은 Velocity packaging plugin을 함께 적용하는 방식으로 만들지 않는다.

기존 YAML은 provider 기반 **legacy fallback**으로 읽는다. 채택한 우선순위는 명시 DSL → legacy YAML → 기존 기본값이다.
기존 YAML의 version-only `api-coordinate`는 해당 host의 원래 group/artifact를 붙여 full coordinate로 변환한다.
명시적인 기존 `springPaper.configurationFile`/`-PspringPaper.configuration` 선택을 보존하되,
Gradle에서 런타임 profile/import/secret까지 descriptor 설정으로 확장하지 않는다. 누락 검증은 해당 task 실행 때 한다.

Boot 런타임 사용을 위해 Boot Gradle plugin을 자동 적용할 필요는 없다.
[bootJar](https://docs.spring.io/spring-boot/gradle-plugin/packaging.html)는 BOOT-INF 구조를 만들므로 flat host JAR과 구별한다.
Shadow에는 services 병합에 더해 Boot `.imports`의 줄 병합, `spring.factories`의 key별 값 병합과
해당 경로의 duplicates INCLUDE가 필요하다. 기존 설정만 쓴 negative control은 실제 자동구성을 잃었다.

## 5. Boot가 맡을 책임과 Paper에 남길 책임

기본안은 표준 `@EnableAutoConfiguration`을 재사용하고 비웹 자동구성 확장을 위한 seam을 남기는 것이다.
**1차 채택한 제품 의존성은 `spring-boot`와 `spring-boot-autoconfigure` 4.1.2 두 모듈뿐**이다.
고정 [autoconfigure POM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-autoconfigure/4.1.2/spring-boot-autoconfigure-4.1.2.pom)은 Boot core만 의존하고,
[imports](https://github.com/spring-projects/spring-boot/blob/v4.1.2/core/spring-boot-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports)는
core/AOP/availability/task 등 12개다. native Boot JDBC/JPA/cache 자동구성은 이 두 모듈에 없다.
1차에는 native Boot DataSource/JPA/JDBC/cache 모듈·starter를 추가하지 않고, 기존 선택 모듈의 구성과 flag를 같은 컨텍스트에서 사용한다.
외부 `spring-boot-starter-jdbc`, `spring-boot-starter-data-jpa`, `spring-boot-starter-cache`나 그 native 자동구성 모듈을
추가한 조합은 1차 지원·수용 범위 밖이다. 이를 추가하면 upstream 기본값이 실행될 수 있으므로 기존 flag의 차단 효과를 약속하지 않는다.
다른 비웹 외부 starter도 별도 host 호환 검증 전에는 지원 조합으로 표시하지 않는다. 확장 seam 제공과 starter 지원 선언을 구별한다.
웹 관련 starter·자동구성·전이 모듈은 확장 후보에도 포함하지 않는다.
작은 whitelist의 `@ImportAutoConfiguration`만 사용하는 안은 초기 통제는 쉽지만 starter를 추가할 때 다시
중앙 목록을 편집하게 하므로 주 기본안으로 권하지 않는다. 레거시 호환은 같은 Boot runtime의 어댑터로 처리한다.

웹 금지는 `WebApplicationType.NONE` 설정만으로 충족했다고 판단하지 않는다.
제품 의존성 그래프와 **최종 plugin JAR에 실제 들어간 모듈·구현 클래스·리소스**를 함께 검사한다.
Spring Web/MVC/WebFlux/Servlet/WebSocket, Boot의 웹 모듈·starter, Tomcat/Jetty/Undertow 등 웹 서버 구현의 유입은 실패다.
Minecraft native Netty transport와 Gradle run-task의 HTTP 다운로드 도구는 웹 프레임워크와 구별한다.

| 검사 위치 | 적용할 비웹 계약 |
|---|---|
| core/config·data/cache/security/PDC·host adapter 제품 runtime | 직접·전이 의존성 모두 웹 모듈을 금지; Boot core+autoconfigure 두 모듈만 도입 |
| Gradle plugin·개발 실행 tooling | 배포 artifact를 준비하는 HTTP 다운로드는 허용; 웹 server/starter/Servlet/MVC/WebFlux를 제품에 추가하지 않음 |
| Paper/Velocity 최종 flat plugin JAR | 의존성 선언 외에 실제 archive도 검사; host API 제외·Boot metadata 보존과 함께 웹 구현 혼입을 실패 처리 |

| Boot에 위임할 것 | Paper/Velocity 어댑터가 소유할 것 |
|---|---|
| ConfigData·profile/import·Binder·configuration metadata | host별 설정 탐색 root, pre-start native 파일 산출 |
| 일반 DI·proxy·조건 평가·일반 빈 lifecycle | native host singleton, TCCL/resource loader와 main-thread handoff |
| 일반 bean backoff·후보 판정 | 명시 자원 선택·alias, Paper 전용 worker/security timer 역할 |
| 표준 DataSource/JPA/JDBC 구성의 가능한 부분은 후속 이관 | 1차는 기존 data 구성·worker-only·스키마·자원 소유권 유지 |
| 기존 Spring caching advice 재사용, Boot provider 이관은 후속 | 기존 transaction-aware 캐시 반영, native 입력·tick·청크·종료 조정 |

내장 모듈의 발견 경로는 Boot `AutoConfiguration.imports`로 연결하되 **기존 enabled 누락=false 조건과 구성 구현**을 유지한다.
변환한 내장 모듈의 SPI 등록은 중복 실행하지 않는다. 기존 사용자 SPI는 기존 진입점 어댑터에서 한 번 평가해
같은 Boot 컨텍스트에 등록하는 호환안을 제안한다. 별도 컨테이너나 새 범용 SPI를 추가하지 않는다.
일반 타입별 `DefaultBeans.backOff`는 `@ConditionalOnMissingBean`으로 제거할 수 있다.
Boot 4.1.2의 [조건 계약](https://raw.githubusercontent.com/spring-projects/spring-boot/v4.1.2/core/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/condition/ConditionalOnMissingBean.java)은
비주입·비기본 후보를 제외한다. 실제 missing/dedicated/default/Fallback/Primary/predicted FactoryBean 대표 proof가 통과했고,
조건 평가 중 제품을 조기 생성하지 않았다. qualifier 값에 따른 worker/timer 선택과 raw FactoryBean 명시 선택은
이 타입 조건과 같지 않으므로 작은 역할별 어댑터의 필요성을 따로 판단한다. BFPP를 일괄 잔존시키지 않는다.

후속 JPA 표준 이관에서 기본 매니저는 Boot [JpaBaseConfiguration](https://raw.githubusercontent.com/spring-projects/spring-boot/v4.1.2/module/spring-boot-jpa/src/main/java/org/springframework/boot/jpa/autoconfigure/JpaBaseConfiguration.java)의
customizers만 믿으면 consumer customizer/집계 교체로 guard가 빠질 수 있다. **제안:** Paper 기본 JPA factory만
모든 Boot customizers 이후 기존 listener 목록을 복사하고 guard를 추가한다. 사용자 manager는 수정하지 않는다.
이 factory를 HibernateJpaAutoConfiguration의 기본 manager보다 먼저 정의해 backoff시키는 조건·순서는 구현 검증 대상이다.
JDBC manager까지 같은 guard를 확대하는 것은 현행 정책이 아니며 이번 안에서 자동 확정하지 않는다.

후속 표준 모듈 이관의 구성·순서 후보는 다음과 같다. 기존 공개 사용 흐름의 수용 검증 후 치환한다.

| 표준 구성 | 적용 순서와 남길 어댑터 |
|---|---|
| DataSourceAutoConfiguration | Boot JDBC 모듈의 풀 생성·설정 재사용; 기존 unused DataSource/명시 소유권 차이는 먼저 결정 |
| TransactionManagerCustomizationAutoConfiguration → HibernateJpaAutoConfiguration | 기본 manager customizers 준비 후 Hibernate 구성; Paper 기본 JPA manager는 native 기본 manager보다 먼저 정의하고 마지막 guard 부착을 검증 |
| HibernateJpaAutoConfiguration → DataSourceTransactionManagerAutoConfiguration | native JPA 기본 manager가 JDBC 기본을 억제하는 순서 유지; 사용자 manager는 변경하지 않음 |
| JdbcTemplateAutoConfiguration → JdbcClientAutoConfiguration | DataSource 뒤 실행; 기존 interface 기반 facade 연결을 표준 concrete 조건과 대조 |
| HibernateJpaAutoConfiguration → DataJpaRepositoriesAutoConfiguration | root package 관례와 명시 EntityScan/EnableJpaRepositories override; legacy 문자열 선택은 호환 어댑터 |
| CacheAutoConfiguration | caching advice 활성화 뒤 provider 선택; 사용자 resolver/manager backoff와 transaction-aware 반영 보존 |

이 순서는 고정 버전 [Hibernate](https://raw.githubusercontent.com/spring-projects/spring-boot/v4.1.2/module/spring-boot-hibernate/src/main/java/org/springframework/boot/hibernate/autoconfigure/HibernateJpaAutoConfiguration.java),
[JDBC](https://raw.githubusercontent.com/spring-projects/spring-boot/v4.1.2/module/spring-boot-jdbc/src/main/java/org/springframework/boot/jdbc/autoconfigure/DataSourceTransactionManagerAutoConfiguration.java),
[Repository](https://raw.githubusercontent.com/spring-projects/spring-boot/v4.1.2/module/spring-boot-data-jpa/src/main/java/org/springframework/boot/data/jpa/autoconfigure/DataJpaRepositoriesAutoConfiguration.java)의 선언을 기준으로 한다.

## 6. 새 모드와 레거시 호환에서 구별할 동작

| 분류 | 보존·변경안 |
|---|---|
| 작은 호환 비용 | 기존 진입점·공개 helper는 같은 Boot bootstrap으로 위임; `PaperConfiguration` 조회 facade와 기존 YAML build 경로 유지 |
| 필수 안전 계약 | 단일 host/컨텍스트, Spring proxy 재사용, 시도한 시작만 정리, 입력 차단→main 대기 해제→사용자 flush→worker drain→인프라 종료, 사용자 자원 이중 종료 금지 |
| startup 실행 계약 | worker refresh와 refresh 완료 뒤 main 활성화 유지; startup `main(...).join()` 교착 위험을 해결됐다고 표시하지 않음. 새 차단·timeout·진단 정책은 후속 합의 |
| 필수 데이터 계약 | 기본 JPA worker-only, 기존 validate DDL·명명 규칙·명시 자원 소유권, transaction-aware 캐시 commit/rollback 유지 |
| 1차 채택 사양 | 새 root package scan+자기 JAR/dataDir의 Boot ConfigData 순서, 기존 선택 모듈의 flag/구성 유지 |
| 후속 결정 | 선택 모듈 classpath 자동 활성화, native Boot DB/캐시 기본 제공자와 새·기존 키 충돌 처리 |

Boot 기본값을 그대로 쓰면 기존과 다른 결과도 있다.
[HibernateProperties](https://raw.githubusercontent.com/spring-projects/spring-boot/v4.1.2/module/spring-boot-hibernate/src/main/java/org/springframework/boot/hibernate/autoconfigure/HibernateProperties.java)는
embedded DB의 DDL과 naming 기본값이 현재 직접 구성과 다르다. 스키마 변경을 암묵적으로 허용하지 않는다.
Boot JDBC의 concrete template 조건은 현재 interface 기반 연결과 다르고, 기존 JdbcClient만 있어도
[DataSource 자동구성](https://raw.githubusercontent.com/spring-projects/spring-boot/v4.1.2/module/spring-boot-jdbc/src/main/java/org/springframework/boot/jdbc/autoconfigure/DataSourceAutoConfiguration.java)은 별도 풀을 만들 수 있다.
1차는 기존 구성을 사용해 이 차이를 도입하지 않는다. 표준 치환과 niche 계약 변경은 후속에 별도 결정한다.

1차 채택한 활성화 사양은 다음과 같다. 새 root의 일반 빈 탐색과 선택 모듈 활성화는 별개 interface다.
root package에 일반 `@Service`를 추가하면 발견되지만 선택 모듈은 classpath에 있다는 이유만으로 켜지지 않는다.
제품 구현·검증 상태는 §11에 별도로 기록한다.

| 경우 | 새 애플리케이션 모드의 기존 선택 모듈 | 기존 경로 보존 목표 |
|---|---|---|
| 선택 의존성 없음 | 해당 자동구성·빈 없음 | 동일 |
| 선택 의존성 있음, flag 없음 | 기존 Module과 동일하게 비활성 | 누락 false 유지 |
| enabled true / false | true일 때 현재 구성, false일 때 해당 자동구성 없음 | 동일 |
| 사용자 기본 빈 있음 | Boot 조건으로 backoff | 동일 인스턴스·기존 종료 소유권 유지 |
| 직접 `@Import`/Enable | 명시적 사용자 구성으로 취급 | 기존 공개 구성 경로의 동작 유지 목표 |

위 표는 채택한 동작 계약이다. 현재 제품은 조건부 AutoConfiguration wrapper를 도입했지만,
기존 제공자의 자원 선택·backoff·alias helper는 호환 계약을 유지하며 남아 있다. 이 보정을 모두
Boot 조건으로 치환했다고 표시하지 않는다. native Boot DB/cache 이관과 함께 후속 검증할 범위다.

자체 wrapper에만 false 조건을 붙여서는 upstream DataSource/JPA 자동구성까지 꺼지지 않는다.
이를 감추는 전역 삭제기나 모든 Boot 구성을 복제하는 filter 목록은 기본안에 넣지 않는다.
1차에는 upstream data 자동구성 자체를 제품 그래프에 넣지 않는다. 후속 표준 이관 때 flag/exclude의 계약을 정한다.

새 root는 Boot ConfigData 순서를 사용하며 기존 진입점의 설정 로딩은 바꾸지 않는다.
[기존 loader](../spring-paper-core/src/main/java/org/blu3fishez/springpaper/config/PaperEnvironment.java#L12)의 지정 resource,
packaged base→external base→각 active profile의 packaged→external overlay와 system property/env 우선순위를 유지한다.
기존 명시 scan·initializers·flag도 보존한다. 같은 Boot runtime으로 위임한다는 이유로 legacy에 새 ConfigData 탐색이나
profile/import 해석을 자동 적용하지 않는다. 새 모드로의 전환은 소비자의 명시적 선택이다.

## 7. typed configuration과 map 이관

소비자는 `@ConfigurationProperties` record/class를 root 아래에 두고 생성자로 주입한다.
프레임워크의 기존 security 초 단위 키·120/10/5초·pending 0의 의미와 전용 scheduler 선택은 우선 보존한다.
Duration형 새 키와 alias를 추가하는 일은 별도 interface 제안이며 자동 이관하지 않는다.

Boot Binder는 env-only 항목을 발견하지만 기존 raw map key를 마법처럼 복원하지 않는다.
실제 `...HIBERNATE_JDBC_BATCH_SIZE=17`은 String map에서 `hibernate.jdbc.batch.size`, Object map에서 nested map이 됐다.
String map의 dotted 설정은 `hibernate.jdbc.batch_size`를 보존하고, Object map은 `"[hibernate.jdbc.batch_size]"`로 보존했다.
정확한 vendor 키에는 bracket+placeholder 또는 `SPRING_APPLICATION_JSON`을 제안한다.
[공식 map 규칙](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.relaxed-binding.maps)과
[고정 버전 mapper](https://raw.githubusercontent.com/spring-projects/spring-boot/v4.1.2/core/spring-boot/src/main/java/org/springframework/boot/context/properties/source/SystemEnvironmentPropertyMapper.java)를 따른다.
새 typed 설정과 기존 raw map 경로를 구별하고, 기존 키를 조용히 다른 vendor 키로 전달하지 않는다.

## 8. application.yml과 native 서버 설정의 실행 시점

다음 키는 1차 채택 사양이며 Paper 앱과 Velocity 프로젝트 각각의 application.yml 예시다.

```yaml
# Paper backend
spring:
  paper:
    velocity:
      enabled: true
      online-mode: true
      secret-file: '${VELOCITY_FORWARDING_SECRET_FILE}'
```

```yaml
# 별도 Velocity 프로젝트
spring:
  velocity:
    server:
      bind: '0.0.0.0:25577'
      online-mode: true
      player-info-forwarding-mode: modern
      forwarding-secret-file: '${VELOCITY_FORWARDING_SECRET_FILE}'
      servers:
        lobby: '127.0.0.1:25565'
        try: [lobby]
```

두 native 서버 모두 글로벌 설정을 플러그인 초기화 **전에** 읽는다. Boot refresh에서 수정하면 늦다.
pre-start prepare/run task 또는 검토 가능한 파일 산출을 담당하게 한다. 기존 proxy의 원격 설정·등록을 자동 변경하지 않는다.
Paper backend의 기본 대상은 `server.properties`의 online-mode=false, `spigot.yml`의 settings.bungeecord=false,
`config/paper-global.yml`의 proxies.velocity.{enabled,online-mode,secret}다.
proxy 소유자가 backend 주소 등록·modern 방식·공유 secret·online-mode 일치를 준비해야 한다.

pre-start parser는 Gradle daemon 안에서 전역 SpringApplication을 실행하지 않는다.
**제안:** forked 작은 helper가 Boot `ConfigDataEnvironmentPostProcessor.applyTo`와 필요한 JSON EPP를 재사용한다.
profile·relative import·env placeholder를 검증했다. 임의의 third-party EnvironmentPostProcessor 전체와의 동일성은 미검증이다.
자기 JAR, plugin dataDir, native server cwd를 하나의 명시적 launch plan으로 두 단계에 전달하는 안을 제안한다.
**1차 추천 탐색 root는 자기 JAR+native plugin dataDir**이며 native server cwd는 암묵적으로 검색하지 않는다.
다른 위치는 명시 ConfigData location/import로 선택한다. runtime과 pre-start가 같은 root·profile·환경 입력을 사용한다.
새 모드는 Boot의 packaged base→packaged profile→external base→external profile 순서를 채택하는 안이다.
따라서 external base가 packaged profile보다 우선한다. §6의 legacy overlay 순서와 구별한다.

파일 정책의 1차 추천은 새 framework-owned run-dir의 native 파일 전체 생성과 생성 후 hash 기반 drift 충돌 감지다.
기존 미소유 파일에는 검토 가능한 plan만 제공한다. 기존 파일의 실제 apply는 1차에서 제외한다.
후속 apply는 명시 관리키·외부 변경 hash 충돌·미관리 map 키·주석·list 교체 범위를 먼저 합의하고 검증한다.
현재 TOML proof는 값·주석 text roundtrip이며 byte-for-byte 보존이나 YAML 병합까지 입증하지 않았다.

Paper의 --config/--spigot-settings/--paper-settings-directory와 --online-mode override를 반영한 실제 경로·값을 먼저 계산한다.
[Paper native secret](https://github.com/PaperMC/Paper/blob/35a2d15/paper-server/src/main/java/io/papermc/paper/configuration/GlobalConfiguration.java#L112)은
nonempty `PAPER_VELOCITY_SECRET`, Velocity는 nonblank `VELOCITY_FORWARDING_SECRET`이 파일 값보다 우선한다.
launcher가 소유한 새 프로세스 env에만 공유 file→native env 매핑을 제안하며, 기존 프로세스 env는 변경하지 않는다.
서로 다른 유효 secret을 발견하면 값을 노출하지 않고 충돌을 보고한다. 자동 생성·회전은 하지 않는다.
secret은 JAR·공개 descriptor·task 로그에 넣지 않고 native 파일 materialization과 구별한다.

## 9. 실행한 proof와 한계

공통 scratch: `/var/folders/qm/ks5hxylx53s_zc97__yj2y700000gn/T/spring-paper-boot-PROTOTYPE-qw34n7e9`
기존 정확한 Gradle classpath와 승인된 Boot 4.1.2 artifact만 사용했다. URL/SHA256은 download manifest에 있다.
최신 bootstrap proof source의 SHA256은 `478eefe8583284fa593163b927f7963ab0688e9f456b15c480a9b109b4b54e8a`다.
`bootstrap-evidence-manifest.json`에 실행 명령·입력·결과 hash·제약을 함께 기록했다. 제품 소스 hash나 native host 검증 결과를 뜻하지 않는다.

| 실제 검증 | 결과와 범위 |
|---|---|
| Boot embedding·순서·실패 | 최신 7시나리오 PASS. ordinary root/service 발견, native activation 뒤 Boot ready/ACCEPTING, context.close→doClose→선행 main cleanup 확인. 모든 실패에 expected/observed와 exact cause를 단언한다. refresh 실패는 Spring 내부 정리가 먼저이며 host는 미활성. |
| Binder·ConfigData parser | env-only/String/Object/bracket/JSON/placeholder, context 없이 profile/import/external 우선순위 PASS. 전체 SpringApplication 환경 확장 동일성을 뜻하지 않는다. |
| Boot bean 조건 | missing/dedicated/비주입/default/Fallback/Primary/predicted FactoryBean 7대표 PASS; 조건 중 제품 생성 0. raw unknown 제품·역할 qualifier 전체는 별도. |
| 실제 flat JAR | service 병합만 한 control은 imports/factories 유실. Boot metadata 병합 뒤 두 factory·두 auto-config bean 발견 PASS. 최종 제품 Shadow JAR/native host 검증은 별도. |
| JPA guard | 실제 Boot customizer 집계와 Hibernate/H2에서 기본 main 거절·worker query·JDBC/사용자 manager 정책 유지 PASS. customizer overwrite/집계 교체로 typed guard가 빠지는 반례와 최종 copy+append 대안도 확인. native Boot JPA factory ordering은 미검증. |
| Velocity bridge·TOML | 실제 native loader/Guice에서 두 JAR bridge/root identity·host 주입 PASS. TOML bool/int/list/quoted key/IPv6/미관리 값·주석 text roundtrip PASS. 실제 proxy 기동·client forwarding은 미검증. |

Boot harness는 fixture listener 하나를 직접 선택하고 일부 cleanup만 연결한다. legacy initializer는 callback fixture이며,
PaperReady는 harness에서 수동 발행한다. 모든 command/scheduler/tick/chunk/security/JPA 종료 조합·최종 loader pipeline을
검증한 것이 아니다. 최신 정상 close는 사전 수동 cleanup 없이 doClose 연결을 확인했고 독립 검토에서도 보강을 확인했다.
JUL root/handler·Boot shutdown hook 범위는 확인했지만 Boot는 java.awt.headless를 설정할 수 있어 전역 영향이 전혀 없다고 하지 않는다.

재현: 공통 scratch의 `run-bootstrap-proof.py`; 소비자 proof의 실제 javac/java/Shadow 명령은
`consumer-proof/replay.py`에 모았다(원 검증 결과는 각 `*-results.json`, metadata/results.json).
Velocity는 `/var/folders/qm/ks5hxylx53s_zc97__yj2y700000gn/T/spring-paper-native-bridge-proof-ipcy7ybw/reproduce.py`,
TOML 자료는 `/var/folders/qm/ks5hxylx53s_zc97__yj2y700000gn/T/spring-paper-velocity-toml-proof-uztnk9uh`에 있다.

## 10. 1차 채택 결정과 수용기준

사용자가 아래 세 추천안을 모두 명시 채택하여 다음 1차 구현을 승인했다.

1. ordinary root와 기존 진입점을 **동일한 Boot bootstrap**으로 연결하고 host singleton·proxy·main handoff·ready·실패 정리를 실제 등록 pipeline에서 검증한다.
2. Paper/Velocity용 target·descriptor·실행 설정을 분리하고, YAML 없는 help/tasks와 target만 지정한 flat JAR assemble을 제공한다.
3. typed 설정·일반 Boot bean 조건으로 대체 가능한 공통 보정 코드를 줄인다. 기존 선택 모듈은 같은 컨텍스트에서 우선 회귀 검증한다.
4. pre-start ConfigData parser·유효 native 설정 plan을 제공하고, 새 owned run-dir 생성+drift 감지와 미소유 파일 plan-only로 prepare/run을 연결한다. 기존 proxy 원격 등록은 포함하지 않는다.

DB·cache·PDC 성숙도 개선은 장기 목표에 유지한다. 후속 단위는 Boot DataSource/JPA/JDBC 표준 구성 이관,
entity/repository 탐색 관례·스키마/guard 보존, cache 기본 제공자·transaction 계약, PDC typed repository 조립이다.
새 기본값을 먼저 깔고 보정층을 계속 추가하는 순서는 피한다. 각 후속 단위의 public seam과 소비자 검증을 정한 뒤 구현한다.

| 수용기준 | 관찰할 결과 |
|---|---|
| 최소 소비자 | YAML 없는 help/tasks; 합의 target만 지정한 assemble; root 아래 새 @Service가 발견됨 |
| native 산출물 | host/root 각각 하나, Boot metadata 병합, host API 제외, Paper/Velocity task·JAR·run-dir 분리 |
| 비웹 확정 조건 | 제품 runtime의 직접·전이 그래프와 양쪽 최종 plugin JAR 모두 웹 모듈·starter·서버 구현 없음; Minecraft Netty/download tooling과 구별 |
| lifecycle | 실제 생성·발견·proxy·부분 실패와 선행 cleanup, Boot ready/입력 활성화, 기존 종료 안전 회귀 통과 |
| 선택 기능·소유권 | flag 누락=false·명시 false 유지, 불필요 자원·secret 요구 없음, default/dedicated/사용자 자원 재사용, 기본 JPA guard·스키마·cache transaction 계약 유지 |
| 새 설정/legacy 분리 | 새 root는 자기 JAR+dataDir·Boot external/profile 순서, legacy는 기존 resource·overlay/profile·명시 scan 유지; cwd 자동 검색 없음 |
| 설정 준비 | profile/env/import·native CLI 반영, owned 파일 drift 충돌 검출, 미소유 파일 plan-only, secret 비노출, 기존 proxy 등록 책임 명시 |

다음 세 묶음은 앞 절의 코드·DSL·YAML 예시를 포함하여 **모두 사용자 명시 채택 완료**다.
앞서 확정된 비웹 조건·배포 형태·Velocity 연결 요구도 유지한다.

| 명시 채택 완료한 선택 | 채택한 1차 사양 |
|---|---|
| 1. Boot 제품 채택과 1차 전환 범위 | `spring-boot`+`spring-boot-autoconfigure` **4.1.2**만 도입, 현재 Framework/Data/Security/Hibernate 고정 조합 유지. 기존 선택 모듈의 누락=false·자원/스키마/worker/cache 계약 보존. native Boot DB/cache 치환·classpath만으로 전체 기능 활성화는 후속 |
| 2. 소비자 interface | §3의 ordinary root/공용 native bridge+own-JAR root metadata·root package/typed properties scan, §4의 host별 DSL 채택. Paper는 full `apiCoordinate`+`apiVersion`, Velocity는 full `apiCoordinate`+명시 실행 `version/buildNumber`; YAML 없는 조회 task와 compile/JAR의 실행 설정·secret 분리. legacy scan/initializers·설정 경로 유지 |
| 3. 설정 탐색과 native 파일 정책 | 새 root의 runtime/pre-start는 자기 JAR+plugin dataDir와 Boot external/profile 순서 공유, implicit cwd 제외. §8 YAML 키 채택. 새 owned run-dir은 전체 생성+drift 충돌, 기존 미소유 파일은 plan-only; 실제 apply·secret 자동 생성/회전은 후속 |

위 세 추천의 명시 채택으로 1차 구현에 필요한 공개 사양을 확정했다. 이후의 “진행/Go”는 이 확정 범위의
실행 승인으로 취급하며, 후속 미정 사양을 임의 선택하는 승인으로 해석하지 않는다.
startup 교착의 새 guard 정책, native Boot 데이터 이관, 기존 파일 apply 같은 후속 선택은 1차를 막는 미정 사양으로 끌어오지 않는다.
기존 서버/프록시 자동 배포·원격 등록·실제 client 접속·범용 동적 toggle·추가 플랫폼 지원은 1차 범위에 넣지 않는다.
run task를 제공하는 구현과 실제 host 프로세스 실행은 구별하며, 현재 실제 실행 승인은 없다.

## 11. 제품 구현과 검증 현황

아래 결과는 저장소의 제품 소스와 실제 소비자 산출물에 대한 검증이며 §9의 임시 prototype proof와 구별한다.
공용 bootstrap은 `WebApplicationType.NONE`, native 소유 종료, Boot logging/shutdown-hook 전역 소유 방지를 강제한다.
Servlet API·Spring Web·웹 컨테이너 runtime 의존성은 금지한다. 허용한 Boot core 자체의 미활성
Servlet/reactive adapter 클래스는 포함되며, 독립 소비자 검증에서 원본 4.1.2 core의 bytecode와 비교한다.

| 제품 경로 | 현재 확인한 상태 |
|---|---|
| 공통 Boot runtime | Boot core+autoconfigure 4.1.2, 고정 Framework 조합, `NONE` 강제·logging listener/shutdown-hook 억제·JUL 보존·직접 close의 native 선행 정리. |
| Paper ordinary/기존 진입점 | ordinary root·typed properties·자기 artifact/dataDir·profile/import·실제 singleton/FactoryBean proxy 입력과 기존 initializer/resource/thread/flag 경로를 같은 bootstrap으로 연결했다. 시작 뒤 off-main 직접 context.close는 즉시 거절하고 정상 main 종료를 보존한다. |
| Velocity 기존 native `@Plugin` + helper | 기존 공개 constructor·명시 initializer·비동기 EventTask를 공통 Boot runtime으로 연결했다. host singleton/TCCL·AOP·입력 부분 실패·자원 종료·startup/shutdown 경합을 유지한다. |
| Velocity ordinary root | 새 `velocity.annotation.SpringVelocityApplication`과 `velocity.bootstrap.SpringVelocityApplicationPlugin` bridge를 구현했다. 버전은 Gradle project.version, root는 id/name/description/url/authors와 type-safe scan marker를 제공한다. |
| Velocity ordinary root 소비자 검증 | root 아래 service·typed properties, 자기 artifact/dataDir의 profile·relative import, singleton FactoryBean의 실제 class proxy 입력, Boot ready/ACCEPTING 전 입력 활성화, ready listener 실패·직접 close의 native 선행 정리. |
| 선택 모듈 | 기본 제공 5개 모듈에 Boot imports+flag 조건 wrapper를 도입했다. 기존 provider의 자원 선택/backoff/alias helper와 JPA worker guard/schema·cache commit/rollback·종료 소유권은 유지하며 native Boot DB/cache 구성으로 치환하지 않았다. ordinary는 사용자 SPI를 만들지 않고 legacy는 기존 사용자 SPI를 한 번 실행한다. |
| 최종 Gradle 산출물·독립 소비자 | 기존 guide·ordinary Paper·ordinary Velocity를 included build 없는 Maven 소비자로 빌드하고 각각 두 번째 configuration cache 재사용을 확인했다. `build/verification-repository`만 사용하며 `~/.m2`·외부 저장소에는 게시하지 않는다. 현재 라이브러리 bytecode 172/84/20개, root descriptor/metadata, Boot imports/factories 병합·중복 entry 0·호스트 API 제외를 확인했다. |
| 최종 실제 예제 JAR·비웹 조건 | 두 ordinary 예제의 실제 resolved runtime graph에 같은 비웹 gate를 적용하고 flat JAR 및 cache 재사용을 확인했다. Boot 모듈은 core+autoconfigure 4.1.2 두 개뿐이며 Spring Web·Servlet API·컨테이너·Reactor Netty와 host API/Guice/SLF4J/Lombok/tooling은 포함하지 않는다. Boot core 자체의 미활성 `boot/web` class 53개는 원본 4.1.2와 byte-for-byte 일치한다. |
| 설정 준비 | 최종 Gradle 38개 테스트가 상대 ConfigData location/import·profile·native cwd, typed YAML→TOML, owned drift/semantic rebaseline, 미소유 plan-only·redacted proposal, native 환경변수/파일 secret 충돌을 검증했다. 실제 Paper 상대 location/import/profile prepare와 미소유 원본 불변·없는 secret source 미생성도 확인했다. legacy Velocity assemble은 유지하고 새 prepare/run은 ordinary-only로 진단한다. |
| 실제 Velocity loader | 기존 고정 build 43 JAR와 최종 ordinary JAR의 JSON loader→PluginClassLoader→Guice→bridge→실제 Boot를 실행해 두 loader의 own metadata/profile·typed service·ready·shutdown과 implicit cwd 제외를 확인했다. controlled bindings·공개 EventTask 직접 호출이며 native plugin-manager dispatch나 proxy 프로세스 실행 결과가 아니다. |

`META-INF/spring-paper/application.properties`의 `host=paper|velocity`, `root-class=<FQCN>`이 공용 root metadata 계약이다.
bridge는 자기 codeSource의 JAR 또는 개발 classes 디렉터리에서만 metadata를 읽는다.
ordinary root는 Spring이 만들며, native bridge만 Guice가 만든다. 동일 root를 native `@Plugin`으로 중복 생성하지 않는다.

최종 검증 명령은 `./gradlew build`, `python3 scripts/verify-published-consumer.py`,
`python3 spring-paper-velocity/src/nativeTest/verify-native-application.py --artifact examples/boot-velocity-plugin/build/libs/spring-velocity-boot-example-0.1.0-SNAPSHOT.jar`다.
새 root quick start는 [Paper](../examples/boot-paper-plugin/README.md), [Velocity](../examples/boot-velocity-plugin/README.md),
설정 순서·기존 경로는 [설정 계약](configuration.md)에 있다.

2026-11-13 최종 `./gradlew build`는 PASS했다. 최신 JUnit XML 합계는 **333개, 실패/오류/skip 0**이다
(Boot 6, Paper core 133, Velocity 21, 선택 5모듈 81, config 4, security-core 50, Gradle plugin 38).
마지막 root 실행은 configuration cache를 재사용했고 111 task 중 12개 실행·99개 up-to-date였다.
테스트 task는 모두 담당별 최신 정상 결과를 up-to-date로 재사용했으며 333개를 새로 실행한 결과는 아니다.
비웹 그래프 gate와 source size 검사는 이 마지막 실행에서 수행했고 Javadoc/plugin validation도 통과했다.
로그·집계는 `build/boot-final-root-build.log`, `build/boot-final-root-verification.json`,
`build/published-consumer-verification.json`, `build/final-example-artifact-verification.json`에 있다.
controlled native loader의 재현 명령·소스/산출물 hash·stdout/stderr는
`build/velocity-verification/boot-application-final-20261113/report.json`과 같은 디렉터리에 보존했다.

현재 전환에서 실제 Paper/Velocity 프로세스 기동, EULA 수락, client 접속/forwarding, 운영 파일 apply,
원격 proxy 등록·secret 자동 생성/회전은 수행하지 않는다. §9의 scratch proof나 이전 host 실행 기록은
이 미실행 범위의 제품 검증을 대신하지 않는다.
