PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = 3000;

CREATE TABLE IF NOT EXISTS settings (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  config TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  mode TEXT NOT NULL CHECK (mode IN ('UDP', 'TCP', 'DEMO')),
  device_ip TEXT NOT NULL,
  started_at TEXT NOT NULL,
  stopped_at TEXT,
  high_threshold REAL NOT NULL,
  jump_threshold REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS measurements (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id TEXT NOT NULL REFERENCES sessions(id),
  sample_id INTEGER NOT NULL,
  received_at TEXT NOT NULL,
  temperature REAL NOT NULL,
  humidity REAL NOT NULL CHECK (humidity BETWEEN 0 AND 100),
  delta REAL NOT NULL,
  segments INTEGER NOT NULL CHECK (segments BETWEEN 1 AND 8),
  high_alert INTEGER NOT NULL CHECK (high_alert IN (0, 1)),
  jump_alert INTEGER NOT NULL CHECK (jump_alert IN (0, 1)),
  mode TEXT NOT NULL CHECK (mode IN ('UDP', 'TCP', 'DEMO')),
  device_ip TEXT NOT NULL,
  UNIQUE (session_id, sample_id)
);

CREATE INDEX IF NOT EXISTS measurements_date ON measurements(received_at);
CREATE INDEX IF NOT EXISTS measurements_mode_date ON measurements(mode, received_at);

CREATE TABLE IF NOT EXISTS events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id TEXT REFERENCES sessions(id),
  created_at TEXT NOT NULL,
  level TEXT NOT NULL CHECK (level IN ('info', 'warning', 'error')),
  message TEXT NOT NULL
);
