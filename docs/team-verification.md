# 최종 팀 통합 검증 — 2026-11-11

운영 코드 기준은 `56b46dea1ec7b462939decbdd5caee2b2e3c2292`다. 구현·수정 담당과
별도로 최종 전체 빌드, 독립 예제, 게시된 Maven 산출물의 소비를 다시 실행했다.
이 문서는 [책임·사용성 검토](architecture-audit.md), [실행 검토](execution-independent-review.md),
[데이터·빌드 검토](data-build-independent-review.md), [자원 소유권 검토](resource-ownership-review.md)의
코드 조사와 실패 재현을 대신하지 않는다.

## 실행 환경과 보존

- Host Temurin `25.0.2+10`, 저장소의 Gradle wrapper, 기존 고정 dependency 버전을 사용했다.
- 모든 Gradle 실행은 worker를 최대 2개로 제한했다. 예제 여섯 개는 순차 실행했다.
- `clean`을 실행하지 않았다. `build/usability-audit`의 원본 재현 자료와 이전 native 증거를 보존했다.
- 새 전체 실행 로그·XML 집계·산출물 SHA-256은 `/tmp/simext90-*-20261111.log`와
  `/tmp/simext90-*-final-*.json`에 기록했다. 이 경로는 임시 증거이며 저장소 문서의 기록을 대체하지 않는다.

## 라이브러리 전체 빌드

```shell
./gradlew build verifySourceSize --rerun-tasks --max-workers=2 --console=plain
```

82개 작업을 전부 실행하여 통과했다. 9개 모듈의 Javadoc·sources JAR, Gradle plugin validation,
600줄 검사와 테스트를 포함한다. XML을 다시 읽은 결과는 다음과 같다.

| 모듈 | 테스트 | 실패·오류·skip |
|---|---:|---:|
| config | 4 | 0 |
| core | 115 | 0 |
| data-jdbc | 11 | 0 |
| data-jpa | 24 | 0 |
| data-pdc | 10 | 0 |
| cache | 8 | 0 |
| security-core | 48 | 0 |
| security | 14 | 0 |
| gradle-plugin | 4 | 0 |
| 합계 | 238 | 0 |

Lombok의 JDK Unsafe 경고와 기존 deprecated API 사용 알림은 남아 있다. 빌드를 실패시키는
Javadoc·plugin validation 오류는 없었다. 라이브러리 JAR·sources·Javadoc 27개의 SHA-256도 저장했다.

## 독립 예제 여섯 개

각 예제에서 다음을 순차 실행했다. 이 예제들은 루트 하위 프로젝트가 아니라 각자의
`settings.gradle`에서 라이브러리를 포함하는 독립 소비자다.

```shell
./gradlew -p examples/<name>-plugin build --rerun-tasks --max-workers=2 --console=plain
```

현재 라이브러리의 모듈별 JAR에서 클래스 집합을 계산한 뒤, 예제에 선택된 모듈이 빠짐없이 들어가고
모든 클래스 바이트가 같은지 대조했다. 이전 문서의 고정 클래스 수를 기대값으로 사용하지 않았다.
SpringPaperModule 공급자 목록·공급자 클래스 존재·중복 공급자도 확인했다.

| 예제 | 현재 라이브러리 클래스 | 테스트 | 빌드·바이트·공급자 검사 |
|---|---:|---:|---|
| minimal | 71 | 4 | 통과 |
| scheduling | 71 | 0 | 통과 |
| jdbc | 75 | 0 | 통과 |
| approval | 117 | 0 | 통과 |
| guide | 154 | 0 | 통과 |
| business | 75 | 6 | 통과 |

예제 테스트 10개는 모두 통과했고 실패·오류·skip은 없다. 모든 예제 JAR에서 중복 ZIP 경로와
Spring Boot/Bukkit/Paper/SLF4J/Lombok/Guice 패키지는 0개다. 테스트가 없는 예제의 build 성공을
그 예제에 대한 단위 테스트 결과로 세지 않는다.

## 게시 산출물을 사용하는 복사 경로

`scripts/verify-published-consumer.py`로 현재 산출물을 격리 local Maven repository에 다시 게시하고,
guide 예제 소스를 새 소비자에 복사하여 `includeBuild` 없이 빌드했다. 최초 소비는
`--refresh-dependencies`를 사용했다. 두 번째 build의 실제 `Reusing configuration cache.`를 확인했다.

- 현재 라이브러리 클래스 154개의 바이트가 전부 일치했다.
- JPA/JDBC/Cache/Security/PDC 공급자 5개가 중복·누락 없이 들어갔다.
- 생성된 plugin 진입점·command permission metadata와 금지 패키지·ZIP 중복 검사를 통과했다.
- JAR SHA-256: `e663debdfafca6057ee714047108cb21c059a6c6f48d9a00e53e8700f1464b63`.
- 상세 결과: `build/published-consumer-verification.json`.

별도 데이터 검토자가 준비한 `build/data-build-independent-review/copied-business`도 현재 게시물로
검증했다. 유지되는 business 예제와 `src` 전체가 동일하며 `includeBuild`가 없다.
최초 build는 dependency refresh와 task 재실행을 사용했고, 두 번째 build는 실제 configuration cache를
재사용했다. 현재 config/core/JDBC 75개 클래스와 앱 10개 클래스의 바이트, JDBC 공급자를 대조했다.
기존 business 테스트 6개도 다시 통과했다. 이 반복 실행을 서로 다른 새 테스트 6개로 합산하지 않는다.

복사 business JAR SHA-256은 `eafdee904e16e716e424bbc06723b441473042f3b2acc9afcf5c0a9822069d5f`로,
유지되는 예제의 JAR과 같다. 중복 ZIP 경로와 앞서 열거한 금지 패키지는 0개다.

## Native Paper 검증

새로 빌드한 여섯 JAR을 기존 격리 host Paper `26.3 build244`에서 실제로 실행했다.
사용자가 앞서 허용한 local EULA 설정을 재사용했으며 추적되는 예제의 `accept-eula: false`는 유지했다.
이전 harness를 `/tmp/simext90-native-final-20261111/native.py`로 복사하여 기존 assertion을 유지하고,
새 로그 경로와 Gradle worker 제한만 적용했다. 이전 native 증거 파일은 덮어쓰지 않았다.

minimal module과 business inbox를 각각 enabled/disabled로 실행한 두 기동이 모두 통과했다.
각 기동은 localhost `25585` 사용 여부를 먼저 검사하고, 성공 marker를 모두 본 뒤 정상 `stop`을 보냈다.
두 프로세스는 exit 0으로 끝났고 포트가 닫혔다. harness의 finally에서 두 isolated feature 설정을 복구했다.

- Scheduling과 virtual-thread worker, utilities, JDBC의 실제 H2 커밋·롤백을 확인했다.
- Approval의 합성 fixture와 guide의 PDC/JPA/Cache smoke 경로가 통과했다.
- minimal의 세 command/alias는 각각 활성화 상태를 반영했다.
- business inbox가 disabled이면 업무 입력을 실행하지 않고 static command help metadata는 남았다.
- 사용자 main 종료 훅 → JPA EntityManagerFactory 종료 → Hikari pool 종료 순서를 확인했다.
- 각 JAR은 앞서 대조한 현재 library 클래스 수 71/71/75/117/154/75와 같은 바이트를 사용했다.

현재 실행 결과는 `/tmp/simext90-native-final-20261111/native-result.json`, 각 console은 같은 폴더의
`native-true.log`·`native-false.log`에 있다. 이는 예제 host 기동·console 검증이며 실제 Minecraft
클라이언트 인증·접속이나 SimpleExtraction 게임플레이 검증은 아니다.

## 범위와 한계

실제 Spring/Hibernate/H2·제어 가능한 Paper 경계·Gradle TestKit과 독립 Maven 소비를 검사했다.
Minecraft 클라이언트 로그인·게임플레이, 운영 MariaDB/Redis 네트워크 장애, 모든 외부 FactoryBean과
executor 조합, 장시간 thread·메모리 부하는 이 결과에 포함하지 않는다. 소비자 서버별 산출물·게임
저장 흐름은 SimpleExtraction의 별도 최종 통합 보고서에서 집계한다.

개발계·운영계 재배포·재기동·레지스트리 push·Portainer webhook 호출·VM 설정 변경은 하지 않았다.
