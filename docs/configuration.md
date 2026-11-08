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

현재 실행 검증 대상은 Paper 26.3/Java 25다. 버전 설정 가능성과 모든 과거/미래 Paper 버전의 호환성은 다르다. 다른 버전은 적절한 API coordinate/JDK와 실제 기동을 검증해야 한다. Folia의 region scheduler는 지원하지 않는다.

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

`spring.paper.security.enabled: true`로 메서드 인가와 입력 문맥을 켠다. 선택적으로 `spring.paper.security.denied-message`를 설정한다.

동기 호출에서 true인 effective permission이 그대로 authority가 된다. OP는 ROLE_OPERATOR, 콘솔은 ROLE_CONSOLE을 추가한다. principal은 Player UUID/이름의 PaperPrincipal이다. 예: `@PreAuthorize("hasAuthority('myplugin.admin')")`.

비동기 이벤트는 자동 caller 권한을 읽지 않는다. 플레이어를 식별하지 못하는 이벤트는 빈 문맥으로 호출한다. 필요한 경우 `PaperInvocationInterceptor` SPI로 불변 호출자 문맥을 전달한다. 작업자에 전달한 문맥은 이후 세션/권한 변경을 자동 재검증하지 않는다.

## 라이브러리 확장

SpringPaperModule SPI는 코어가 호출하고 선택 모듈이 구현한다. META-INF/services 등록과 enabledProperty/configurationClass를 제공하면 활성화된 모듈 구성만 컨텍스트에 들어온다. provider 생성자는 I/O와 자원 생성을 하지 않는다.

PaperInvocationInterceptor SPI는 코어의 이벤트·명령·탭 완성 경로를 감싼다. Spring orderedStream 순서를 따른다. 실제 빈은 생성자 주입하며, 어노테이션을 읽기 위해 프록시를 벗겨 원본을 호출하지 않는다.

컨텍스트 준비, 사용자 start/stop, 입력 호출과 PaperTasks 작업은 플러그인의 문맥 클래스 로더를 사용하고 이전 로더를 복원한다. 다른 플러그인의 컨텍스트나 전역 정적 컨텍스트를 재사용하지 않는다.

Paper 입력 어댑터와 사용자 lifecycle 훅은 eager singleton이어야 한다. lazy/prototype이면 조용히 누락시키지 않고 시작 오류로 알린다. 비활성 서버 역할·인증 모드는 조건부 설정에서 제외한다. 일반 서비스와 지연 자원은 해당 조건 안에서 기존 지연 초기화를 유지할 수 있다. 코어가 제공하는 paperPlugin/paperServer/paperLogger/paperConfiguration/paperServerInfo/paperThreadAccess 이름은 재정의하지 않는다.

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

서버 API와 SLF4J 구현은 Shadow JAR에 포함하지 않는다. Spring/Hibernate 및 SPI 서비스 메타데이터는 소비자 JAR에 포함·병합한다. 호환되지 않는 Spring 버전을 여러 플러그인이 동시에 사용하는 환경이나 별도 relocation 정책은 현재 검증 범위에 포함되지 않는다.
