CREATE TABLE IF NOT EXISTS owner_notification_preferences (
  owner_id TEXT PRIMARY KEY,
  startup_enabled INTEGER NOT NULL DEFAULT 1,
  shutdown_enabled INTEGER NOT NULL DEFAULT 1,
  agent_stop_enabled INTEGER NOT NULL DEFAULT 1,
  sleep_enabled INTEGER NOT NULL DEFAULT 1,
  wake_enabled INTEGER NOT NULL DEFAULT 1,
  offline_enabled INTEGER NOT NULL DEFAULT 1,
  command_failed_enabled INTEGER NOT NULL DEFAULT 1,
  updated_at INTEGER NOT NULL,
  FOREIGN KEY (owner_id) REFERENCES owners(id)
);

CREATE TABLE IF NOT EXISTS owner_notifications (
  id TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  pc_id TEXT,
  type TEXT NOT NULL,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  payload_json TEXT,
  is_read INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  read_at INTEGER,
  FOREIGN KEY (owner_id) REFERENCES owners(id),
  FOREIGN KEY (pc_id) REFERENCES pc_devices(id)
);

CREATE INDEX IF NOT EXISTS idx_owner_notifications_owner_created
  ON owner_notifications (owner_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_owner_notifications_owner_read
  ON owner_notifications (owner_id, is_read, created_at DESC);
