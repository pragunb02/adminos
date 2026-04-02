-- 006: Notifications domain — reminders, notifications, notification_preferences

CREATE TABLE reminders (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  type              reminder_type NOT NULL,
  title             VARCHAR(255) NOT NULL,
  body              TEXT,
  entity_type       VARCHAR(50),
  entity_id         UUID,
  remind_at         TIMESTAMPTZ NOT NULL,
  recurrence        recurrence NOT NULL DEFAULT 'once',
  recurrence_rule   JSONB,
  status            reminder_status NOT NULL DEFAULT 'scheduled',
  sent_at           TIMESTAMPTZ,
  channel           notification_channel NOT NULL DEFAULT 'push',
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_remind_at ON reminders (remind_at) WHERE status = 'scheduled';
CREATE INDEX idx_remind_user ON reminders (user_id);

CREATE TABLE notifications (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id         UUID REFERENCES devices(id) ON DELETE SET NULL,
  type              notification_type NOT NULL,
  title             VARCHAR(255) NOT NULL,
  body              TEXT NOT NULL,
  data              JSONB,
  channel           notification_channel NOT NULL,
  fcm_message_id    VARCHAR(255),
  status            notification_status NOT NULL DEFAULT 'pending',
  sent_at           TIMESTAMPTZ,
  delivered_at      TIMESTAMPTZ,
  clicked_at        TIMESTAMPTZ,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notif_user ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notif_status ON notifications (status, sent_at);

CREATE TABLE notification_preferences (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  weekly_briefing   BOOLEAN NOT NULL DEFAULT true,
  briefing_day      weekday NOT NULL DEFAULT 'mon',
  briefing_time     TIME NOT NULL DEFAULT '08:00',
  bill_reminders    BOOLEAN NOT NULL DEFAULT true,
  bill_reminder_days INTEGER[] NOT NULL DEFAULT '{3,1}',
  anomaly_alerts    BOOLEAN NOT NULL DEFAULT true,
  subscription_flags BOOLEAN NOT NULL DEFAULT true,
  email_notifications BOOLEAN NOT NULL DEFAULT true,
  push_notifications BOOLEAN NOT NULL DEFAULT true,
  quiet_hours_start TIME NOT NULL DEFAULT '22:00',
  quiet_hours_end   TIME NOT NULL DEFAULT '08:00',
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE notification_preferences ADD CONSTRAINT uq_notif_pref UNIQUE (user_id);
