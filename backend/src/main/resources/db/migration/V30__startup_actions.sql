-- Things the application was told to do once at start-up (for example FAMS_RESET_ALL_PASSWORDS=<token>). A row is written
-- before the action runs, so the same token never runs twice, however often the application restarts.
CREATE TABLE startup_actions (
  action VARCHAR(190) NOT NULL PRIMARY KEY,
  done_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
