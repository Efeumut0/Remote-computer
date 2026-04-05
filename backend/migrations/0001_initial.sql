CREATE TABLE IF NOT EXISTS owners (
  id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS pc_devices (
  id TEXT PRIMARY KEY,
  machine_id TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  platform TEXT NOT NULL,
  app_version TEXT,
  secret_hash TEXT NOT NULL,
  owner_id TEXT,
  pairing_code TEXT,
  pairing_code_expires_at INTEGER,
  status TEXT NOT NULL DEFAULT 'offline',
  last_seen_at INTEGER,
  last_event_type TEXT,
  metadata_json TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  FOREIGN KEY (owner_id) REFERENCES owners(id)
);

CREATE INDEX IF NOT EXISTS idx_pc_devices_owner_id ON pc_devices (owner_id);
CREATE INDEX IF NOT EXISTS idx_pc_devices_pairing_code ON pc_devices (pairing_code);

CREATE TABLE IF NOT EXISTS mobile_devices (
  id TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  name TEXT NOT NULL,
  fcm_token TEXT NOT NULL,
  platform TEXT NOT NULL DEFAULT 'android',
  last_seen_at INTEGER,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE(owner_id, fcm_token),
  FOREIGN KEY (owner_id) REFERENCES owners(id)
);

CREATE INDEX IF NOT EXISTS idx_mobile_devices_owner_id ON mobile_devices (owner_id);

CREATE TABLE IF NOT EXISTS commands (
  id TEXT PRIMARY KEY,
  pc_id TEXT NOT NULL,
  owner_id TEXT NOT NULL,
  type TEXT NOT NULL,
  payload_json TEXT,
  status TEXT NOT NULL,
  result_json TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  dispatched_at INTEGER,
  completed_at INTEGER,
  FOREIGN KEY (pc_id) REFERENCES pc_devices(id),
  FOREIGN KEY (owner_id) REFERENCES owners(id)
);

CREATE INDEX IF NOT EXISTS idx_commands_pc_status_created ON commands (pc_id, status, created_at);

CREATE TABLE IF NOT EXISTS events (
  id TEXT PRIMARY KEY,
  pc_id TEXT NOT NULL,
  type TEXT NOT NULL,
  payload_json TEXT,
  created_at INTEGER NOT NULL,
  FOREIGN KEY (pc_id) REFERENCES pc_devices(id)
);

CREATE INDEX IF NOT EXISTS idx_events_pc_created ON events (pc_id, created_at DESC);
