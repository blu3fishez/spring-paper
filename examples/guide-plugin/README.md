# 종합 Spring 생태계 검증 예제

처음 읽을 때는 [최소 예제](../minimal-plugin/README.md)부터 시작한다.
이 플러그인은 여러 라이브러리 기능의 통합 검증용이며 일반 업무 서비스의 최소 코드량을 뜻하지 않는다.
[공통 빌드/실행 방법](../README.md#빌드와-실행)을 따른다.

Done 직후 자동으로 한 번 검증하고 SPRING_PAPER_SMOKE_OK를 출력한다.
콘솔 `springpaper`와 alias `sp`로 반복할 수 있다.

- Note/NoteRepository/NoteService: Spring Data JPA·@Transactional·동일 풀의 JdbcClient와
  Cacheable/CachePut/CacheEvict를 확인한다.
- PDC 도메인과 repository: entity/item 필드 mapping·변환·clone/보존을 확인한다.
- listener: 메서드 어노테이션과 조립 구성의 before/after 관계를 확인한다.
- lifecycle 서비스/관계: 준비→사용→해제와 Spring 인프라 종료 순서를 확인한다.
- OperatorService: 표준 Spring Security 메서드 인가를 확인한다.
- security 패키지: 커스텀 필터의 pending→authenticated→closed fixture를 확인한다.

성공 시 plugins/SpringPaperExample/smoke-success.txt에 JPA/JDBC/cache/PDC/이벤트/인가 마커를 기록한다.
종료 때 smoke-stop.txt와 SPRING_PAPER_CUSTOM_STOP이 먼저 남고 EntityManagerFactory·Hikari가 닫힌다.

종합 예제의 연결 matcher는 합성 fixture만 허용한다. 실제 연결은 기본 거절된다.
실제 Paper 접속 승인 흐름은 [approval-plugin](../approval-plugin/README.md)을 사용한다.
JDBC만 필요하면 [jdbc-plugin](../jdbc-plugin/README.md)을 사용해 JPA 의존성을 생략한다.
