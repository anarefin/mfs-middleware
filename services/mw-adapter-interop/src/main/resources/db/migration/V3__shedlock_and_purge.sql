-- ShedLock lock table (scheduled jobs run on one instance only) and the indexes the outbox and
-- inbox purge job deletes by (spec 6.4, 16.3). ShedLock keeps UTC database time in timestamp
-- columns (JdbcTemplateLockProvider.usingDbTime).
CREATE TABLE shedlock (
  name        varchar(64)  NOT NULL PRIMARY KEY,
  lock_until  timestamp    NOT NULL,
  locked_at   timestamp    NOT NULL,
  locked_by   varchar(255) NOT NULL
);

CREATE INDEX outbox_sent_at_idx ON outbox (sent_at) WHERE sent_at IS NOT NULL;
CREATE INDEX inbox_processed_at_idx ON inbox (processed_at);
