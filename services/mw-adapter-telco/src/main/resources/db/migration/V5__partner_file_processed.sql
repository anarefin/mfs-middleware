-- The files the batch partner routes processed (ticket 86): Camel's JDBC idempotent repository of
-- the mw-sftp-source Kamelet (bean mwSftpFiles, camel-sql JdbcMessageIdRepository, column names as
-- Camel's), so a reconciliation file is read once, even by two route versions polling during a
-- swap. messageid is <partnerId>/<file name>-<size>-<modified>; no personal data.
CREATE TABLE partner_file_processed (
    processorname varchar(255) NOT NULL,
    messageid     varchar(512) NOT NULL,
    createdat     timestamp    NOT NULL,
    PRIMARY KEY (processorname, messageid)
);
