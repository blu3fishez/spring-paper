# JDBC 전용 트랜잭션 예제

[공통 빌드/실행 방법](../README.md#빌드와-실행)을 따른다. 콘솔에서 `paper-jdbc`를 실행한다.
H2의 두 계좌 100/50에서 25를 이체하고 75/75를 읽는다. 다음 이체를 debit 뒤에 실패시켜
잔액이 75/75로 롤백되는 것을 확인한다.

출력: `JDBC_OK committed=75/75 rollback=75/75 jpa=absent`.

예제는 소비자 소유 EmbeddedDatabase와 표준 JdbcTransactionManager를 선언한다.
Spring Paper는 JdbcClient/Template을 연결하고, 트랜잭션 경계는 별도 TransferService의
@Transactional 메서드에 둔다. Spring 프록시 때문에 이 서비스는 final이 아니다.
명령은 PaperTasks.async로 DB 작업을 실행하고 main으로 결과를 전달한다.

라이브러리가 JPA/JDBC 트랜잭션 소유자를 임의 선택하지 않도록 매니저는 소비자가 정한다.
JPA와 함께 쓸 때는 같은 DataSource의 JpaTransactionManager를 재사용한다.
H2 외부 서버나 계정은 필요 없다. 종료 때 예제 DB가 닫힌다.
