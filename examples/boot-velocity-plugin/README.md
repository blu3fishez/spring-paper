# Ordinary Velocity application

Java 25, Velocity 4.2.1-SNAPSHOT build 43, Spring Boot 4.1.2 비웹 예제다.
`@SpringVelocityApplication(id, name)` ordinary root 아래 일반 service와 typed properties를 자동 발견한다.
공용 native bridge와 자기 JAR의 root metadata를 사용하며, native `@Plugin` annotation processor는 필요 없다.

```shell
./gradlew -p examples/boot-velocity-plugin help tasks
./gradlew -p examples/boot-velocity-plugin assemble
```

위 명령은 proxy를 시작하지 않고 secret도 읽지 않는다. YAML 파일이 없어도 target만으로 조립할 수 있다.
결과는 `build/libs/spring-velocity-boot-example-0.1.0-SNAPSHOT.jar`인 flat plugin JAR다.
`springVelocity.apiCoordinate`는 full G:A:V compile 좌표이며 실행 target과 분리된다.
`runVelocity`에는 `development.version`과 `buildNumber`를 모두 명시해야 한다. run-task 3.0.2의
`velocityVersion(...)`/`build(...)`와 대응하며 latest를 선택하지 않는다. 실제 proxy 기동은 검증하지 않았다.

`application.yml`의 `spring.velocity.server`는 native TOML map이다. boolean·정수·list의 타입과
`servers.try` 순서를 보존한다. literal dotted key에는 Boot map bracket 표기를 사용한다.

```yaml
spring:
  velocity:
    server:
      bind: '0.0.0.0:25577'
      online-mode: true
      player-info-forwarding-mode: modern
      forwarding-secret-file: '${VELOCITY_FORWARDING_SECRET_FILE}'
      servers:
        lobby: '127.0.0.1:25565'
        '[island.test]': '[::1]:25566'
        try: [lobby, island.test]
```

`VELOCITY_FORWARDING_SECRET_FILE`에 기존 공유 파일을 지정한 뒤 `prepareVelocityServer`로 native 파일을
준비한다. 상대 경로는 native run 디렉터리가 기준이다. launcher는 기존 파일 내용을 새 Velocity 프로세스의
`VELOCITY_FORWARDING_SECRET`에 전달한다. 값은 JAR·descriptor·계획·로그에 쓰지 않으며 자동 생성/회전하지
않는다. 기존 실효 native 환경변수와 다르면 값을 숨긴 충돌로 중단한다. 파일 경로 placeholder 대신
기존 `VELOCITY_FORWARDING_SECRET`을 직접 전달할 수도 있다. 파일과 실효 native 환경변수가 모두 없으면
자동 secret 생성을 막기 위해 owned 준비/실행을 중단한다. build/help에는 이 조건이 적용되지 않는다.

runtime과 pre-start는 자기 JAR와 `run/plugins/spring-paper-boot-velocity`의 ConfigData만 기본 탐색한다.
native cwd는 기본 탐색하지 않는다. `SPRING_PROFILES_ACTIVE`, 명시 location/import, JavaExec의
system properties/environment를 두 단계에서 공유한다. 외부 base는 packaged profile보다 우선한다.
native TOML 위치는 `run/velocity.toml`이고 forwarding-secret-file도 native cwd 기준이다.
명시 상대 `file:` location/import도 native run 디렉터리 기준이며 helper가 같은 cwd를 쓴다.

새/빈 `development.directory`만 자동 소유한다. 도구가 쓴 native 파일의 hash를 기록해 수동 drift를
차단한다. 미소유 디렉터리는 `build/spring-velocity/native-plan.txt`와 proposal을 산출하고 원본을 변경하지 않는다.
그 상태에서 run도 중단한다. 성공한 run의 native default 추가/포맷 변화는 원래 생성한 typed 값을
보존했을 때만 종료 후 hash에 반영한다. 실패나 기존 값 변경은 반영하지 않는다. 실행 중 외부 편집과
native 기본값 추가를 완전히 구별하지 않으므로 도구 소유 파일은 실행 중 편집하지 않는다.
계획 옆 `native-proposal.json`에 대상 경로·hash·typed 제안 값과 forwarding secret source/environment
정책을 기록한다. 민감 키는 `<redacted>`이며 secret-file 경로는 표시한다. plan-only에서는 source 파일을 열지 않는다.

Paper backend 등록과 원격 proxy 파일 변경은 proxy owner가 수행한다. 별도 Gradle 프로젝트에 하나의
host를 두며 Paper packaging plugin을 함께 적용하지 않는다. TOML tooling은 고정 native build의
[NightConfig 3.9.0](https://github.com/PaperMC/Velocity/blob/9a5951c7/gradle/libs.versions.toml#L56)과 동일하며
host plugin JAR에는 포함하지 않는다. native secret 우선순위·상대 경로는 고정
[VelocityConfiguration.read](https://github.com/PaperMC/Velocity/blob/9a5951c7/proxy/src/main/java/com/velocitypowered/proxy/config/VelocityConfiguration.java#L462)를 따른다.
