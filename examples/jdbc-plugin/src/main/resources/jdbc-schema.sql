CREATE TABLE example_accounts (id VARCHAR(16) PRIMARY KEY, balance INT NOT NULL CHECK (balance >= 0));
INSERT INTO example_accounts VALUES ('source', 100), ('target', 50);
