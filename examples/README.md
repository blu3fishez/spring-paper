# 기능별 예제 플러그인

Paper 예제는 Java 25·Paper 26.3 build 244·Spring Framework 7.0.10·Lombok 1.18.48을 사용한다.
별도 Velocity 예제는 Java 25·Velocity 4.2.1-SNAPSHOT·Spring Framework 7.0.10을 사용한다.
각 폴더가 독립 Gradle 소비 프로젝트이며 `includeBuild('../..')`로 현재 라이브러리를 사용한다.
공통 프레임워크를 한 플러그인에 모두 넣지 않아도 되는 예제들이다.

| 예제 | 보여주는 기능 | 콘솔 검증 |
|---|---|---|
| [business-plugin](business-plugin/README.md) | @Scheduled 만료 정리·안전한 DB→main·소비자 worker/decorator·전용/기본 JDBC 공존·앱 내부 조건부 기능 | paper-inbox |
| [minimal-plugin](minimal-plugin/README.md) | 진입점·생성자 주입·@Value·initializer·profile·외부 설정·선택 Module SPI·리스너·명령/alias/반복 메타데이터·시작/종료 | paper-hello, paper-hi, paper-welcome |
| [scheduling-plugin](scheduling-plugin/README.md) | @Scheduled 메인/백그라운드, TaskScheduler 일회성/취소, tick 작업과 자체 취소, @Async, executor, 키·메타데이터·기존 config·청크 lease | paper-scheduling |
| [jdbc-plugin](jdbc-plugin/README.md) | 소비자 DataSource·JdbcClient·@Transactional 커밋/롤백, JPA 없는 classpath | paper-jdbc |
| [approval-plugin](approval-plugin/README.md) | ProviderManager·필터 체인·커스텀 인증 증거/저장소/대기/결과 처리·접속 어댑터·메서드 인가 | paper-approval verify |
| [guide-plugin](guide-plugin/README.md) | Spring Data JPA·공유 JDBC 트랜잭션·Cacheable/CachePut/CacheEvict·entity/item PDC·이벤트 순서 관계·생명주기 관계·메서드 인가·두 단계 인증 fixture | springpaper, sp |
| [velocity-plugin](velocity-plugin/README.md) | native Guice 진입점·bootstrap·실제 호스트 빈·활성 listener/command·표준 scheduler | spring-velocity, sv |

Core, config, Gradle plugin, JDBC, JPA, Cache, PDC, Security와 host-neutral Security Core의
대표 공개 기능을 이 표에서 찾을 수 있다. Spring 생태계 전체 API를 복제하거나 모든 조합을 제공하는 목록은 아니다.
Velocity bootstrap은 독립 `spring-paper-velocity`가 제공한다. 인증 서비스 호출은 security-core와
소비자 Provider·이벤트 어댑터를 같은 컨텍스트에 조립한다.

복사할 업무 흐름은 **[business-plugin](business-plugin/README.md)**부터 시작한다.
기본 worker가 충분하면 커스텀 executor 구성을 생략한다. 기존 scheduling/guide 예제의 counter와
검증 Future·start/stop은 통합 검증 장치이며 최소 업무 구현의 필수 코드가 아니다.

## 빌드와 실행

저장소 루트에서 예를 들어 다음을 실행한다.

```sh
./gradlew -p examples/minimal-plugin build
```

실행할 때는 해당 예제의 `src/main/resources/application.yml`을 예제 루트의
`application-local.yml`로 복사한다. Minecraft EULA에 수락한 경우에만 로컬 파일의
`spring.paper.development.accept-eula`를 true로 바꾼다. 배포 설정의 기본값은 false다.
필요하면 같은 로컬 파일에서 directory·server-ip·server-port·jvm-args를 바꾼다.

```sh
./gradlew -p examples/minimal-plugin -PspringPaper.configuration=application-local.yml runServer
```

Paper가 Done을 출력한 뒤 콘솔 명령을 실행하고 `stop`으로 정상 종료한다.
모든 예제의 기본 포트는 127.0.0.1:25585다. 각각 실행하거나 다른 예제의 shaded JAR을
한 격리 서버의 plugins 폴더에 복사해서 함께 확인할 수 있다. plain JAR은 배포물이 아니다.
서버 운영 데이터가 있는 폴더에 예제를 섞지 않는다.

2026-11-10 검증에서는 격리 서버에 다섯 예제를 함께 올려 각 마커와 정상 종료를 확인했다.
실제 플레이어 클라이언트 로그인·Discord OAuth·Velocity forwarding은 이 콘솔 검증에 포함하지 않는다.
자세한 근거는 [생산성 검증](../docs/productivity.md)을 참고한다.

2026-11-11에는 업무 예제를 포함한 여섯 예제를 함께 기동하고 선택 module/inbox의 true/false를 각각 확인했다.
[추가 실행·빈 계약 검증](../docs/usability-contracts.md)에 범위와 비용을 기록했다.
