import { DurableObject } from "cloudflare:workers";

type PcStatus = "online" | "offline" | "sleeping";
type EventType =
  | "startup"
  | "shutdown"
  | "agent-stop"
  | "logoff"
  | "sleep"
  | "wake"
  | "manual-refresh"
  | "online"
  | "offline"
  | "clipboard-sync"
  | "command-result";
type NotificationPreferenceKey = "startup" | "shutdown" | "agentStop" | "sleep" | "wake" | "offline" | "commandFailed";

interface Env {
  DEVICE_DB: D1Database;
  SESSION_HUB: DurableObjectNamespace<AgentSessionHub>;
  FILE_BUCKET?: R2Bucket;
  FIREBASE_PROJECT_ID?: string;
  FIREBASE_CLIENT_EMAIL?: string;
  FIREBASE_PRIVATE_KEY?: string;
  PAIRING_TTL_MINUTES?: string;
}

interface PcRecord {
  id: string;
  machine_id: string;
  name: string;
  platform: string;
  app_version: string | null;
  secret_hash: string;
  owner_id: string | null;
  pairing_code: string | null;
  pairing_code_expires_at: number | null;
  status: PcStatus;
  last_seen_at: number | null;
  last_event_type: string | null;
  metadata_json: string | null;
  created_at: number;
  updated_at: number;
}

interface MobileDeviceRecord {
  id: string;
  owner_id: string;
  name: string;
  fcm_token: string;
  platform: string;
  last_seen_at: number | null;
  created_at: number;
  updated_at: number;
}

interface NotificationPreferencesRecord {
  owner_id: string;
  startup_enabled: number;
  shutdown_enabled: number;
  agent_stop_enabled: number;
  sleep_enabled: number;
  wake_enabled: number;
  offline_enabled: number;
  command_failed_enabled: number;
  updated_at: number;
}

interface OwnerNotificationRecord {
  id: string;
  owner_id: string;
  pc_id: string | null;
  type: string;
  title: string;
  body: string;
  payload_json: string | null;
  is_read: number;
  created_at: number;
  read_at: number | null;
}

interface CommandRecord {
  id: string;
  pc_id: string;
  owner_id: string;
  type: string;
  payload_json: string | null;
  status: string;
  result_json: string | null;
  created_at: number;
  updated_at: number;
  dispatched_at: number | null;
  completed_at: number | null;
}

interface PcRegisterRequest {
  machineId: string;
  deviceName: string;
  platform: string;
  appVersion?: string;
  agentSecret?: string;
}

interface MobilePairRequest {
  pairingCode: string;
  deviceName: string;
  fcmToken: string;
}

interface MobileTokenRequest {
  deviceName: string;
  fcmToken: string;
}

interface NotificationSettingsRequest {
  startupEnabled?: boolean;
  shutdownEnabled?: boolean;
  agentStopEnabled?: boolean;
  sleepEnabled?: boolean;
  wakeEnabled?: boolean;
  offlineEnabled?: boolean;
  commandFailedEnabled?: boolean;
}

interface NotificationReadRequest {
  notificationId?: string;
  markAll?: boolean;
}

interface QueueCommandRequest {
  pcId: string;
  type: string;
  payload?: Record<string, unknown>;
}

interface ReserveFileRequest {
  pcId: string;
  direction: "mobile-upload" | "pc-download";
  fileName: string;
}

interface AgentSocketEnvelope {
  type: "agent-event" | "command-result" | "ping";
  eventType?: EventType;
  payload?: Record<string, unknown>;
  commandId?: string;
  success?: boolean;
  error?: string | null;
}

interface PendingCommandMessage {
  type: "command";
  commandId: string;
  commandType: string;
  payload: Record<string, unknown>;
  createdAt: number;
}

interface NotificationPayload {
  title: string;
  body: string;
  type?: string;
  pcId?: string | null;
  pcName?: string | null;
  preferenceKey?: NotificationPreferenceKey | null;
  data?: Record<string, string>;
}

interface FcmDispatchPayload {
  notification?: {
    title: string;
    body: string;
  };
  data: Record<string, string>;
}

interface TokenCache {
  token: string;
  expiresAt: number;
}

let firebaseTokenCache: TokenCache | null = null;
let schemaInitPromise: Promise<void> | null = null;

const JSON_HEADERS = { "content-type": "application/json; charset=utf-8" };
const UNEXPECTED_DISCONNECT_GRACE_MS = 12_000;
const ONLINE_STALE_AFTER_MS = 95_000;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      const url = new URL(request.url);

      if (request.method === "GET" && url.pathname === "/health") {
        return json({
          ok: true,
          service: "uzaktanbildirim-worker",
          time: Date.now(),
          capabilities: {
            r2: Boolean(env.FILE_BUCKET),
          },
        });
      }

      await ensureDatabaseSchema(env);

      if (request.method === "POST" && url.pathname === "/api/pc/register") {
        const body = await readJson<PcRegisterRequest>(request);
        return handlePcRegister(env, request, body);
      }

      if (request.method === "POST" && url.pathname === "/api/pc/pairing-code") {
        const agent = await requireAgent(env, request);
        return handleRefreshPairingCode(env, agent);
      }

      if (request.method === "POST" && url.pathname === "/api/pc/unpair") {
        const agent = await requireAgent(env, request);
        return handleUnpairPc(env, agent);
      }

      if (request.method === "GET" && url.pathname === "/api/pc/connect") {
        const agent = await requireAgent(env, request);
        const id = env.SESSION_HUB.idFromName(agent.id);
        const stub = env.SESSION_HUB.get(id);
        const upstream = new Request(`https://internal/internal/connect?pcId=${encodeURIComponent(agent.id)}`, {
          headers: request.headers,
        });
        try {
          return await stub.fetch(upstream);
        } catch {
          throw new HttpError(
            503,
            "Agent session hub is unavailable. Durable Object migration or Worker config may be incomplete.",
          );
        }
      }

      if (request.method === "POST" && url.pathname === "/api/mobile/pair") {
        const body = await readJson<MobilePairRequest>(request);
        return handleMobilePair(env, request, body);
      }

      if (request.method === "POST" && url.pathname === "/api/mobile/token") {
        const owner = await requireOwner(env, request);
        const body = await readJson<MobileTokenRequest>(request);
        return handleMobileTokenUpdate(env, owner.id, body);
      }

      if (request.method === "GET" && url.pathname === "/api/mobile/notification-settings") {
        const owner = await requireOwner(env, request);
        return handleGetNotificationSettings(env, owner.id);
      }

      if (request.method === "POST" && url.pathname === "/api/mobile/notification-settings") {
        const owner = await requireOwner(env, request);
        const body = await readJson<NotificationSettingsRequest>(request);
        return handleUpdateNotificationSettings(env, owner.id, body);
      }

      if (request.method === "GET" && url.pathname === "/api/mobile/notification-center") {
        const owner = await requireOwner(env, request);
        const limit = Number.parseInt(url.searchParams.get("limit") ?? "40", 10);
        return handleNotificationCenter(env, owner.id, limit);
      }

      if (request.method === "POST" && url.pathname === "/api/mobile/notification-center/read") {
        const owner = await requireOwner(env, request);
        const body = await readJson<NotificationReadRequest>(request);
        return handleMarkNotificationsRead(env, owner.id, body);
      }

      if (request.method === "POST" && url.pathname === "/api/mobile/files/reserve") {
        const owner = await requireOwner(env, request);
        const body = await readJson<ReserveFileRequest>(request);
        return handleReserveFile(env, request, owner.id, body);
      }

      if (
        (request.method === "PUT" || request.method === "GET" || request.method === "DELETE") &&
        url.pathname.startsWith("/api/mobile/files/object/")
      ) {
        const owner = await requireOwner(env, request);
        const objectKey = decodeURIComponent(url.pathname.slice("/api/mobile/files/object/".length)).trim();
        return handleOwnerFileObject(env, request, owner.id, objectKey);
      }

      if (request.method === "GET" && url.pathname === "/api/mobile/pcs") {
        const owner = await requireOwner(env, request);
        return handleListPcs(env, owner.id);
      }

      if (request.method === "GET" && url.pathname === "/api/mobile/usage-summary") {
        const owner = await requireOwner(env, request);
        return handleUsageSummary(env, owner.id);
      }

      if (request.method === "POST" && url.pathname === "/api/mobile/pc/unpair") {
        const owner = await requireOwner(env, request);
        const body = await readJson<{ pcId: string }>(request);
        return handleOwnerUnpairPc(env, owner.id, body.pcId);
      }

      if (request.method === "GET" && url.pathname.startsWith("/api/mobile/commands/")) {
        const owner = await requireOwner(env, request);
        const commandId = decodeURIComponent(url.pathname.slice("/api/mobile/commands/".length)).trim();
        return handleGetCommand(env, owner.id, commandId);
      }

      if (request.method === "POST" && url.pathname === "/api/mobile/commands") {
        const owner = await requireOwner(env, request);
        const body = await readJson<QueueCommandRequest>(request);
        return handleQueueCommand(env, owner.id, body);
      }

      if (
        (request.method === "PUT" || request.method === "GET" || request.method === "DELETE") &&
        url.pathname.startsWith("/api/pc/files/object/")
      ) {
        const agent = await requireAgent(env, request);
        const objectKey = decodeURIComponent(url.pathname.slice("/api/pc/files/object/".length)).trim();
        return handleAgentFileObject(env, request, agent, objectKey);
      }

      return json({ ok: false, error: "Not found" }, 404);
    } catch (error) {
      return toErrorResponse(error);
    }
  },
};

export class AgentSessionHub extends DurableObject<Env> {
  private pcId: string | null = null;
  private socket: WebSocket | null = null;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);

    const existingSockets = this.ctx.getWebSockets();
    if (existingSockets.length > 0) {
      this.socket = existingSockets[0];
      const attachment = this.socket.deserializeAttachment() as { pcId?: string } | null;
      this.pcId = attachment?.pcId ?? null;
    }
  }

  async fetch(request: Request): Promise<Response> {
    try {
      await ensureDatabaseSchema(this.env);
      const url = new URL(request.url);

      if (url.pathname === "/internal/connect") {
        const pcId = url.searchParams.get("pcId");
        if (!pcId) {
          throw new HttpError(400, "pcId query param is required");
        }

        const pair = new WebSocketPair();
        const client = pair[0];
        const server = pair[1];

        if (this.socket) {
          try {
            this.socket.close(1012, "Superseded by a newer connection");
          } catch {
            // Ignore close failures.
          }
        }

        this.ctx.acceptWebSocket(server);
        server.serializeAttachment({ pcId });
        this.pcId = pcId;
        this.socket = server;

        await setPcStatus(this.env, pcId, "online", "online");
        await recordEvent(this.env, pcId, "online", { source: "websocket-connect" });
        this.ctx.waitUntil(dispatchPendingCommands(this.env, pcId, server));

        return new Response(null, { status: 101, webSocket: client });
      }

      if (url.pathname === "/internal/dispatch") {
        const body = await readJson<{ pcId: string }>(request);
        const pcId = body.pcId;
        if (!pcId) {
          throw new HttpError(400, "pcId is required");
        }

        if (!this.socket || this.pcId !== pcId) {
          return json({ ok: false, status: "offline" }, 409);
        }

        const dispatched = await dispatchPendingCommands(this.env, pcId, this.socket);
        return json({ ok: true, dispatched });
      }

      return json({ ok: false, error: "Not found" }, 404);
    } catch (error) {
      console.error("AgentSessionHub.fetch failed", error);
      return toErrorResponse(error);
    }
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    try {
      await ensureDatabaseSchema(this.env);
      const text = typeof message === "string" ? message : new TextDecoder().decode(message);
      let payload: AgentSocketEnvelope;

      try {
        payload = JSON.parse(text) as AgentSocketEnvelope;
      } catch {
        ws.send(JSON.stringify({ type: "error", message: "Invalid JSON payload." }));
        return;
      }

      const attachment = ws.deserializeAttachment() as { pcId?: string } | null;
      const pcId = attachment?.pcId ?? this.pcId;
      if (!pcId) {
        ws.send(JSON.stringify({ type: "error", message: "Missing pcId binding." }));
        return;
      }

      switch (payload.type) {
        case "ping":
          await setPcHeartbeat(this.env, pcId);
          break;
        case "agent-event":
          await handleAgentEvent(this.env, pcId, payload.eventType ?? "manual-refresh", payload.payload ?? {});
          break;
        case "command-result":
          if (!payload.commandId) {
            ws.send(JSON.stringify({ type: "error", message: "commandId is required for command-result." }));
            return;
          }

          await completeCommand(this.env, pcId, payload.commandId, payload.success ?? false, payload.payload ?? {}, payload.error);
          break;
        default:
          ws.send(JSON.stringify({ type: "error", message: "Unsupported message type." }));
          break;
      }
    } catch (error) {
      console.error("AgentSessionHub.webSocketMessage failed", error);
      try {
        ws.send(JSON.stringify({ type: "error", message: coerceErrorMessage(error) }));
      } catch {
        // Ignore socket send failures after an internal error.
      }
    }
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    try {
      await ensureDatabaseSchema(this.env);
      const attachment = ws.deserializeAttachment() as { pcId?: string } | null;
      const pcId = attachment?.pcId ?? this.pcId;
      if (!pcId) {
        return;
      }

      if (ws !== this.socket) {
        return;
      }

      const previousPcState = await queryFirst<Pick<PcRecord, "last_event_type" | "updated_at">>(
        this.env.DEVICE_DB,
        "SELECT last_event_type, updated_at FROM pc_devices WHERE id = ?1",
        pcId,
      );

      this.socket = null;
      this.ctx.waitUntil(this.handleSocketDisconnect(pcId, previousPcState));
    } catch (error) {
      console.error("AgentSessionHub.webSocketClose failed", error);
    }
  }

  private async handleSocketDisconnect(
    pcId: string,
    previousPcState: Pick<PcRecord, "last_event_type" | "updated_at"> | null,
  ): Promise<void> {
    const isUnexpectedDisconnect = shouldSendOfflineNotification(previousPcState);
    if (isUnexpectedDisconnect) {
      await delay(UNEXPECTED_DISCONNECT_GRACE_MS);

      if (this.socket && this.pcId === pcId) {
        return;
      }

      const currentPcState = await queryFirst<Pick<PcRecord, "status" | "last_seen_at" | "updated_at">>(
        this.env.DEVICE_DB,
        "SELECT status, last_seen_at, updated_at FROM pc_devices WHERE id = ?1",
        pcId,
      );

      if (!currentPcState) {
        return;
      }

      if (currentPcState.status !== "online") {
        return;
      }

      if (isPcRecentlySeen(currentPcState.last_seen_at, UNEXPECTED_DISCONNECT_GRACE_MS)) {
        return;
      }
    }

    await setPcStatus(this.env, pcId, "offline", "offline");
    await recordEvent(this.env, pcId, "offline", {
      source: "websocket-close",
      graceful: !isUnexpectedDisconnect,
    });

    if (!isUnexpectedDisconnect) {
      return;
    }

    await notifyPcStatus(this.env, pcId, {
      title: "PC baglantisi kesildi",
      body: "Bilgisayar cevrimdisi gorunuyor.",
      type: "offline",
      preferenceKey: "offline",
    });
  }
}

async function handlePcRegister(env: Env, request: Request, body: PcRegisterRequest): Promise<Response> {
  ensureString(body.machineId, "machineId");
  ensureString(body.deviceName, "deviceName");
  ensureString(body.platform, "platform");

  const now = Date.now();
  const pairingTtlMs = parseInt(env.PAIRING_TTL_MINUTES ?? "15", 10) * 60_000;
  const existing = await queryFirst<PcRecord>(
    env.DEVICE_DB,
    "SELECT * FROM pc_devices WHERE machine_id = ?1",
    body.machineId.trim(),
  );

  const incomingSecret = body.agentSecret?.trim() || null;
  const metadata = JSON.stringify({ ip: request.headers.get("cf-connecting-ip") ?? null });

  if (existing) {
    if (!incomingSecret || !(await constantTimeEqualsHash(existing.secret_hash, incomingSecret))) {
      throw new HttpError(401, "Agent secret is missing or invalid.");
    }

    const pairing = getPairingState(existing, now, pairingTtlMs);
    await env.DEVICE_DB
      .prepare(
        `UPDATE pc_devices
         SET name = ?1,
             platform = ?2,
             app_version = ?3,
             pairing_code = ?4,
             pairing_code_expires_at = ?5,
             metadata_json = ?6,
             updated_at = ?7
         WHERE id = ?8`,
      )
      .bind(
        body.deviceName.trim(),
        body.platform.trim(),
        body.appVersion?.trim() ?? null,
        pairing.code,
        pairing.expiresAt,
        metadata,
        now,
        existing.id,
      )
      .run();

    return json({
      ok: true,
      pc: {
        id: existing.id,
        name: body.deviceName.trim(),
        status: getEffectivePcStatus(existing),
        ownerPaired: Boolean(existing.owner_id),
        pairingCode: existing.owner_id ? null : pairing.code,
        pairingCodeExpiresAt: existing.owner_id ? null : pairing.expiresAt,
      },
      agentSecret: null,
      websocketUrl: buildAgentWebSocketUrl(request, existing.id),
    });
  }

  const pcId = createId("pc");
  const agentSecret = createSecret();
  const secretHash = await sha256Hex(agentSecret);
  const pairingCode = createPairingCode();
  const pairingCodeExpiresAt = now + pairingTtlMs;

  await env.DEVICE_DB
    .prepare(
      `INSERT INTO pc_devices (
         id,
         machine_id,
         name,
         platform,
         app_version,
         secret_hash,
         owner_id,
         pairing_code,
         pairing_code_expires_at,
         status,
         last_seen_at,
         last_event_type,
         metadata_json,
         created_at,
         updated_at
       ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, NULL, ?7, ?8, 'offline', NULL, NULL, ?9, ?10, ?10)`,
    )
    .bind(
      pcId,
      body.machineId.trim(),
      body.deviceName.trim(),
      body.platform.trim(),
      body.appVersion?.trim() ?? null,
      secretHash,
      pairingCode,
      pairingCodeExpiresAt,
      metadata,
      now,
    )
    .run();

  return json({
    ok: true,
    pc: {
      id: pcId,
      name: body.deviceName.trim(),
      status: "offline",
      ownerPaired: false,
      pairingCode,
      pairingCodeExpiresAt,
    },
    agentSecret,
    websocketUrl: buildAgentWebSocketUrl(request, pcId),
  });
}

async function handleRefreshPairingCode(env: Env, agent: PcRecord): Promise<Response> {
  if (agent.owner_id) {
    return json({
      ok: true,
      pc: {
        id: agent.id,
        name: agent.name,
        status: getEffectivePcStatus(agent),
        ownerPaired: true,
        pairingCode: null,
        pairingCodeExpiresAt: null,
      },
    });
  }

  const pairingTtlMs = parseInt(env.PAIRING_TTL_MINUTES ?? "15", 10) * 60_000;
  const pairingCode = createPairingCode();
  const expiresAt = Date.now() + pairingTtlMs;

  await env.DEVICE_DB
    .prepare("UPDATE pc_devices SET pairing_code = ?1, pairing_code_expires_at = ?2, updated_at = ?3 WHERE id = ?4")
    .bind(pairingCode, expiresAt, Date.now(), agent.id)
    .run();

  return json({
    ok: true,
    pc: {
      id: agent.id,
      name: agent.name,
      status: getEffectivePcStatus(agent),
      ownerPaired: false,
      pairingCode,
      pairingCodeExpiresAt: expiresAt,
    },
  });
}

async function handleUnpairPc(env: Env, agent: PcRecord): Promise<Response> {
  return unpairPcRecord(env, agent, agent.owner_id, true);
}

async function handleOwnerUnpairPc(env: Env, ownerId: string, pcId: string): Promise<Response> {
  ensureString(pcId, "pcId");
  const pc = await queryFirst<PcRecord>(
    env.DEVICE_DB,
    "SELECT * FROM pc_devices WHERE id = ?1 AND owner_id = ?2",
    pcId.trim(),
    ownerId,
  );

  if (!pc) {
    throw new HttpError(404, "PC not found for this owner.");
  }

  return unpairPcRecord(env, pc, ownerId, false);
}

async function unpairPcRecord(env: Env, pc: PcRecord, ownerId: string | null, notifyFormerOwner: boolean): Promise<Response> {
  const now = Date.now();
  const pairingTtlMs = parseInt(env.PAIRING_TTL_MINUTES ?? "15", 10) * 60_000;
  const pairingCode = createPairingCode();
  const expiresAt = now + pairingTtlMs;

  await env.DEVICE_DB.batch([
    env.DEVICE_DB
      .prepare(
        `UPDATE pc_devices
         SET owner_id = NULL,
             pairing_code = ?1,
             pairing_code_expires_at = ?2,
             updated_at = ?3
         WHERE id = ?4`,
      )
      .bind(pairingCode, expiresAt, now, pc.id),
    env.DEVICE_DB
      .prepare(
        `UPDATE commands
         SET status = 'cancelled',
             result_json = ?1,
             completed_at = ?2,
             updated_at = ?2
         WHERE pc_id = ?3
           AND status IN ('queued', 'dispatched')`,
      )
      .bind(
        JSON.stringify({
          success: false,
          error: "Pairing was removed before this command executed.",
        }),
        now,
        pc.id,
      ),
  ]);

  await recordEvent(env, pc.id, "unpaired", { previousOwnerId: ownerId });

  if (ownerId && notifyFormerOwner) {
    await notifyOwner(env, ownerId, {
      title: "PC eslemesi kaldirildi",
      body: `${pc.name} artik bu telefonla eslesik degil.`,
      type: "pair-removed",
      pcId: pc.id,
      pcName: pc.name,
      data: { pcId: pc.id, type: "pair-removed" },
    });
  }

  return json({
    ok: true,
    pc: {
      id: pc.id,
      name: pc.name,
      status: getEffectivePcStatus(pc),
      ownerPaired: false,
      pairingCode,
      pairingCodeExpiresAt: expiresAt,
    },
  });
}

async function handleMobilePair(env: Env, request: Request, body: MobilePairRequest): Promise<Response> {
  ensureString(body.pairingCode, "pairingCode");
  ensureString(body.deviceName, "deviceName");
  ensureString(body.fcmToken, "fcmToken");

  const now = Date.now();
  const pc = await queryFirst<PcRecord>(
    env.DEVICE_DB,
    "SELECT * FROM pc_devices WHERE pairing_code = ?1",
    body.pairingCode.trim().toUpperCase(),
  );

  if (!pc || !pc.pairing_code_expires_at || pc.pairing_code_expires_at < now) {
    throw new HttpError(404, "Pairing code is invalid or expired.");
  }

  if (pc.owner_id) {
    throw new HttpError(409, "This computer is already paired.");
  }

  const currentOwnerToken = getBearerToken(request)?.trim() ?? "";
  const existingOwner = await findOwnerByToken(env, currentOwnerToken);
  const ownerId = existingOwner?.id ?? createId("owner");
  const ownerToken = existingOwner ? currentOwnerToken : createSecret(48);

  const updates = [
    env.DEVICE_DB
      .prepare(
        `UPDATE pc_devices
         SET owner_id = ?1,
             pairing_code = NULL,
             pairing_code_expires_at = NULL,
             updated_at = ?2
         WHERE id = ?3`,
      )
      .bind(ownerId, now, pc.id),
  ];

  if (!existingOwner) {
    const ownerTokenHash = await sha256Hex(ownerToken);
    updates.unshift(
      env.DEVICE_DB.prepare("INSERT INTO owners (id, token_hash, created_at, updated_at) VALUES (?1, ?2, ?3, ?3)").bind(
        ownerId,
        ownerTokenHash,
        now,
      ),
    );
  }

  await env.DEVICE_DB.batch(updates);
  await upsertMobileDevice(env, ownerId, body.deviceName.trim(), body.fcmToken.trim(), now);

  await notifyOwner(env, ownerId, {
    title: "Eslestirme tamamlandi",
    body: `${pc.name} artik telefonuna baglandi.`,
    type: "pair-complete",
    pcId: pc.id,
    pcName: pc.name,
    data: { pcId: pc.id, type: "pair-complete" },
  });

  return json({
    ok: true,
    ownerToken,
    ownerId,
    pc: {
      id: pc.id,
      name: pc.name,
      status: getEffectivePcStatus(pc),
      lastSeenAt: pc.last_seen_at,
    },
  });
}

async function handleMobileTokenUpdate(env: Env, ownerId: string, body: MobileTokenRequest): Promise<Response> {
  ensureString(body.deviceName, "deviceName");
  ensureString(body.fcmToken, "fcmToken");

  const now = Date.now();
  await upsertMobileDevice(env, ownerId, body.deviceName.trim(), body.fcmToken.trim(), now);

  return json({ ok: true });
}

async function upsertMobileDevice(
  env: Env,
  ownerId: string,
  deviceName: string,
  fcmToken: string,
  now: number,
): Promise<void> {
  const normalizedName = deviceName.trim();
  const normalizedToken = fcmToken.trim();

  await env.DEVICE_DB
    .prepare("DELETE FROM mobile_devices WHERE fcm_token = ?1 AND owner_id != ?2")
    .bind(normalizedToken, ownerId)
    .run();

  const existing = await queryFirst<MobileDeviceRecord>(
    env.DEVICE_DB,
    "SELECT * FROM mobile_devices WHERE owner_id = ?1 AND fcm_token = ?2",
    ownerId,
    normalizedToken,
  );

  if (existing) {
    await env.DEVICE_DB
      .prepare("UPDATE mobile_devices SET name = ?1, last_seen_at = ?2, updated_at = ?2 WHERE id = ?3")
      .bind(normalizedName, now, existing.id)
      .run();
    return;
  }

  await env.DEVICE_DB
    .prepare(
      `INSERT INTO mobile_devices (id, owner_id, name, fcm_token, platform, last_seen_at, created_at, updated_at)
       VALUES (?1, ?2, ?3, ?4, 'android', ?5, ?5, ?5)`,
    )
    .bind(createId("mobile"), ownerId, normalizedName, normalizedToken, now)
    .run();
}

async function handleGetNotificationSettings(env: Env, ownerId: string): Promise<Response> {
  const settings = await getNotificationSettings(env, ownerId);
  return json({
    ok: true,
    settings: formatNotificationSettings(settings),
  });
}

async function handleUpdateNotificationSettings(
  env: Env,
  ownerId: string,
  body: NotificationSettingsRequest,
): Promise<Response> {
  const current = await getNotificationSettings(env, ownerId);
  const next = {
    startupEnabled: body.startupEnabled ?? Boolean(current.startup_enabled),
    shutdownEnabled: body.shutdownEnabled ?? Boolean(current.shutdown_enabled),
    agentStopEnabled: body.agentStopEnabled ?? Boolean(current.agent_stop_enabled),
    sleepEnabled: body.sleepEnabled ?? Boolean(current.sleep_enabled),
    wakeEnabled: body.wakeEnabled ?? Boolean(current.wake_enabled),
    offlineEnabled: body.offlineEnabled ?? Boolean(current.offline_enabled),
    commandFailedEnabled: body.commandFailedEnabled ?? Boolean(current.command_failed_enabled),
  };

  await env.DEVICE_DB
    .prepare(
      `INSERT INTO owner_notification_preferences (
         owner_id,
         startup_enabled,
         shutdown_enabled,
         agent_stop_enabled,
         sleep_enabled,
         wake_enabled,
         offline_enabled,
         command_failed_enabled,
         updated_at
       ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
       ON CONFLICT(owner_id) DO UPDATE SET
         startup_enabled = excluded.startup_enabled,
         shutdown_enabled = excluded.shutdown_enabled,
         agent_stop_enabled = excluded.agent_stop_enabled,
         sleep_enabled = excluded.sleep_enabled,
         wake_enabled = excluded.wake_enabled,
         offline_enabled = excluded.offline_enabled,
         command_failed_enabled = excluded.command_failed_enabled,
         updated_at = excluded.updated_at`,
    )
    .bind(
      ownerId,
      next.startupEnabled ? 1 : 0,
      next.shutdownEnabled ? 1 : 0,
      next.agentStopEnabled ? 1 : 0,
      next.sleepEnabled ? 1 : 0,
      next.wakeEnabled ? 1 : 0,
      next.offlineEnabled ? 1 : 0,
      next.commandFailedEnabled ? 1 : 0,
      Date.now(),
    )
    .run();

  return json({
    ok: true,
    settings: next,
  });
}

async function handleReserveFile(env: Env, request: Request, ownerId: string, body: ReserveFileRequest): Promise<Response> {
  requireFileBucket(env);
  ensureString(body.pcId, "pcId");
  ensureString(body.fileName, "fileName");

  if (body.direction !== "mobile-upload" && body.direction !== "pc-download") {
    throw new HttpError(400, "direction is invalid.");
  }

  const pc = await queryFirst<PcRecord>(
    env.DEVICE_DB,
    "SELECT id, owner_id FROM pc_devices WHERE id = ?1 AND owner_id = ?2",
    body.pcId.trim(),
    ownerId,
  );

  if (!pc) {
    throw new HttpError(404, "PC not found for this owner.");
  }

  const safeFileName = sanitizeFileName(body.fileName);
  const objectKey = `owners/${ownerId}/pc/${pc.id}/transfers/${body.direction}/${createId("obj")}/${safeFileName}`;
  const objectPath = `/api/mobile/files/object/${encodeURIComponent(objectKey)}`;

  return json({
    ok: true,
    objectKey,
    fileName: safeFileName,
    uploadUrl: buildAbsoluteUrl(request, objectPath),
    downloadUrl: buildAbsoluteUrl(request, objectPath),
  });
}

async function handleListPcs(env: Env, ownerId: string): Promise<Response> {
  const result = await queryAll<Record<string, unknown>>(
    env.DEVICE_DB,
    `SELECT id, name, platform, app_version, status, last_seen_at, last_event_type, created_at, updated_at
     FROM pc_devices
     WHERE owner_id = ?1
     ORDER BY created_at DESC`,
    ownerId,
  );

  return json({
    ok: true,
    pcs: result.map((pc) => {
      const lastSeenAt =
        typeof pc.last_seen_at === "number"
          ? pc.last_seen_at
          : toInt(pc.last_seen_at as string | number | null | undefined);

      return {
        id: pc.id,
        name: pc.name,
        platform: pc.platform,
        appVersion: pc.app_version,
        status: getEffectivePcStatus({
          status: (pc.status as PcStatus | null) ?? "offline",
          last_seen_at: lastSeenAt,
        }),
        lastSeenAt,
        lastEventType: pc.last_event_type,
        createdAt: pc.created_at,
        updatedAt: pc.updated_at,
      };
    }),
  });
}

async function handleUsageSummary(env: Env, ownerId: string): Promise<Response> {
  const windowStart = Date.now() - 24 * 60 * 60 * 1000;
  const requestBudget = 100_000;

  const commandStats =
    (await env.DEVICE_DB
      .prepare(
        `SELECT
           COUNT(*) AS command_count,
           COALESCE(
             SUM(
               CAST(
                 COALESCE(
                   json_extract(result_json, '$.payload.size'),
                   json_extract(result_json, '$.payload.sizeBytes'),
                   0
                 ) AS INTEGER
               )
             ),
             0
           ) AS transferred_bytes
         FROM commands
         WHERE owner_id = ?1
           AND created_at >= ?2`,
      )
      .bind(ownerId, windowStart)
      .first<{ command_count: number | string | null; transferred_bytes: number | string | null }>()) ?? {
      command_count: 0,
      transferred_bytes: 0,
    };

  const eventStats =
    (await env.DEVICE_DB
      .prepare(
        `SELECT COUNT(*) AS event_count
         FROM events
         WHERE pc_id IN (SELECT id FROM pc_devices WHERE owner_id = ?1)
           AND created_at >= ?2`,
      )
      .bind(ownerId, windowStart)
      .first<{ event_count: number | string | null }>()) ?? { event_count: 0 };

  const approxRequestsUsed = toInt(commandStats.command_count) + toInt(eventStats.event_count);
  const approxRequestsRemaining = Math.max(0, requestBudget - approxRequestsUsed);
  const approxTransferredBytes = Math.max(0, toInt(commandStats.transferred_bytes));

  return json({
    ok: true,
    usage: {
      windowHours: 24,
      requestBudget,
      approxRequestsUsed,
      approxRequestsRemaining,
      approxTransferredBytes,
      note:
        "Request sayisi yaklasiktir. Veri tarafinda R2 icin sabit gunluk veri limiti yok; gosterilen sayi son 24 saatte aktardigin tahmini veridir.",
    },
  });
}

async function handleNotificationCenter(env: Env, ownerId: string, limit: number): Promise<Response> {
  const safeLimit = Math.min(Math.max(Number.isFinite(limit) ? limit : 40, 1), 100);
  const notifications = await queryAll<OwnerNotificationRecord>(
    env.DEVICE_DB,
    `SELECT id, owner_id, pc_id, type, title, body, payload_json, is_read, created_at, read_at
     FROM owner_notifications
     WHERE owner_id = ?1
     ORDER BY created_at DESC
     LIMIT ?2`,
    ownerId,
    safeLimit,
  );

  const unread =
    (await env.DEVICE_DB
      .prepare("SELECT COUNT(*) AS unread_count FROM owner_notifications WHERE owner_id = ?1 AND is_read = 0")
      .bind(ownerId)
      .first<{ unread_count: number | string | null }>()) ?? { unread_count: 0 };

  return json({
    ok: true,
    unreadCount: toInt(unread.unread_count),
    notifications: notifications.map((notification) => ({
      id: notification.id,
      pcId: notification.pc_id,
      type: notification.type,
      title: notification.title,
      body: notification.body,
      payload: safeJsonParse<Record<string, unknown>>(notification.payload_json, {}),
      isRead: Boolean(notification.is_read),
      createdAt: notification.created_at,
      readAt: notification.read_at,
    })),
  });
}

async function handleMarkNotificationsRead(env: Env, ownerId: string, body: NotificationReadRequest): Promise<Response> {
  const now = Date.now();
  if (body.markAll) {
    await env.DEVICE_DB
      .prepare("UPDATE owner_notifications SET is_read = 1, read_at = ?1 WHERE owner_id = ?2 AND is_read = 0")
      .bind(now, ownerId)
      .run();
  } else {
    ensureString(body.notificationId, "notificationId");
    await env.DEVICE_DB
      .prepare(
        "UPDATE owner_notifications SET is_read = 1, read_at = ?1 WHERE owner_id = ?2 AND id = ?3",
      )
      .bind(now, ownerId, body.notificationId!.trim())
      .run();
  }

  return json({ ok: true });
}

async function handleQueueCommand(env: Env, ownerId: string, body: QueueCommandRequest): Promise<Response> {
  ensureString(body.pcId, "pcId");
  ensureString(body.type, "type");

  const pc = await queryFirst<PcRecord>(
    env.DEVICE_DB,
    "SELECT * FROM pc_devices WHERE id = ?1 AND owner_id = ?2",
    body.pcId.trim(),
    ownerId,
  );
  if (!pc) {
    throw new HttpError(404, "PC not found for this owner.");
  }

  const now = Date.now();
  const commandId = createId("cmd");
  const payloadJson = JSON.stringify(body.payload ?? {});

  await env.DEVICE_DB
    .prepare(
      `INSERT INTO commands (id, pc_id, owner_id, type, payload_json, status, result_json, created_at, updated_at, dispatched_at, completed_at)
       VALUES (?1, ?2, ?3, ?4, ?5, 'queued', NULL, ?6, ?6, NULL, NULL)`,
    )
    .bind(commandId, pc.id, ownerId, body.type.trim(), payloadJson, now)
    .run();

  const id = env.SESSION_HUB.idFromName(pc.id);
  const stub = env.SESSION_HUB.get(id);
  let dispatchResponse: Response;
  try {
    dispatchResponse = await stub.fetch("https://session/internal/dispatch", {
      method: "POST",
      body: JSON.stringify({ pcId: pc.id }),
    });
  } catch {
    throw new HttpError(
      503,
      "Agent session hub is unavailable. Durable Object migration or Worker config may be incomplete.",
    );
  }
  const dispatchResult = (await dispatchResponse.json<{ ok: boolean; status?: string; dispatched?: number }>()) ?? {};

  return json({
    ok: true,
    commandId,
    status: dispatchResult.status ?? (dispatchResult.dispatched ? "dispatched" : "queued"),
  });
}

async function handleGetCommand(env: Env, ownerId: string, commandId: string): Promise<Response> {
  ensureString(commandId, "commandId");

  const command = await queryFirst<CommandRecord>(
    env.DEVICE_DB,
    "SELECT * FROM commands WHERE id = ?1 AND owner_id = ?2",
    commandId,
    ownerId,
  );

  if (!command) {
    throw new HttpError(404, "Command not found.");
  }

  return json({
    ok: true,
    command: {
      id: command.id,
      pcId: command.pc_id,
      type: command.type,
      status: command.status,
      payload: safeJsonParse<Record<string, unknown>>(command.payload_json, {}),
      result: safeJsonParse<Record<string, unknown> | null>(command.result_json, null),
      createdAt: command.created_at,
      updatedAt: command.updated_at,
      dispatchedAt: command.dispatched_at,
      completedAt: command.completed_at,
    },
  });
}

async function handleOwnerFileObject(env: Env, request: Request, ownerId: string, objectKey: string): Promise<Response> {
  const bucket = requireFileBucket(env);
  validateOwnerObjectKey(objectKey, ownerId);
  return handleR2ObjectRequest(bucket, request, objectKey);
}

async function handleAgentFileObject(env: Env, request: Request, agent: PcRecord, objectKey: string): Promise<Response> {
  const bucket = requireFileBucket(env);
  if (!agent.owner_id) {
    throw new HttpError(409, "PC is not paired with an owner.");
  }

  validateAgentObjectKey(objectKey, agent.owner_id, agent.id);
  return handleR2ObjectRequest(bucket, request, objectKey);
}

async function handleR2ObjectRequest(bucket: R2Bucket, request: Request, objectKey: string): Promise<Response> {
  ensureString(objectKey, "objectKey");

  switch (request.method) {
    case "PUT": {
      if (!request.body) {
        throw new HttpError(400, "Request body is required.");
      }

      await bucket.put(objectKey, request.body, {
        httpMetadata: {
          contentType: request.headers.get("content-type") ?? "application/octet-stream",
        },
      });

      return json({ ok: true, objectKey });
    }
    case "GET": {
      const object = await bucket.get(objectKey);
      if (!object) {
        throw new HttpError(404, "Object not found.");
      }

      const headers = new Headers();
      object.writeHttpMetadata(headers);
      headers.set("etag", object.httpEtag);
      headers.set("cache-control", "no-store");

      return new Response(object.body, { status: 200, headers });
    }
    case "DELETE":
      await bucket.delete(objectKey);
      return json({ ok: true, objectKey });
    default:
      throw new HttpError(405, "Method not allowed.");
  }
}

async function handleAgentEvent(env: Env, pcId: string, eventType: EventType, payload: Record<string, unknown>): Promise<void> {
  const status =
    eventType === "sleep"
      ? "sleeping"
      : eventType === "shutdown" || eventType === "agent-stop" || eventType === "logoff"
        ? "offline"
        : eventType === "wake" || eventType === "startup"
          ? "online"
          : undefined;

  if (status) {
    await setPcStatus(env, pcId, status, eventType);
  } else {
    await setPcHeartbeat(env, pcId, eventType);
  }

  await recordEvent(env, pcId, eventType, payload);

  if (eventType === "clipboard-sync") {
    const changedAt =
      typeof payload.changedAt === "number" || typeof payload.changedAt === "string"
        ? payload.changedAt
        : null;
    await notifyClipboardSync(env, pcId, toInt(changedAt));
    return;
  }

  const notification = getNotificationForEvent(eventType);
  if (notification) {
    await notifyPcStatus(env, pcId, notification);
  }
}

async function dispatchPendingCommands(env: Env, pcId: string, socket: WebSocket): Promise<number> {
  const pending = await queryAll<CommandRecord>(
    env.DEVICE_DB,
    `SELECT * FROM commands
     WHERE pc_id = ?1 AND status = 'queued'
     ORDER BY created_at ASC
     LIMIT 25`,
    pcId,
  );

  if (pending.length === 0) {
    return 0;
  }

  let dispatched = 0;
  for (const command of pending) {
    const message: PendingCommandMessage = {
      type: "command",
      commandId: command.id,
      commandType: command.type,
      payload: safeJsonParse<Record<string, unknown>>(command.payload_json, {}),
      createdAt: command.created_at,
    };

    socket.send(JSON.stringify(message));

    await env.DEVICE_DB
      .prepare("UPDATE commands SET status = 'dispatched', dispatched_at = ?1, updated_at = ?1 WHERE id = ?2")
      .bind(Date.now(), command.id)
      .run();

    dispatched += 1;
  }

  return dispatched;
}

async function completeCommand(
  env: Env,
  pcId: string,
  commandId: string,
  success: boolean,
  payload: Record<string, unknown>,
  error: string | null | undefined,
): Promise<void> {
  const now = Date.now();
  const record = await queryFirst<CommandRecord>(env.DEVICE_DB, "SELECT * FROM commands WHERE id = ?1 AND pc_id = ?2", commandId, pcId);
  if (!record) {
    return;
  }

  const result = { success, error: error ?? null, payload };
  await env.DEVICE_DB
    .prepare("UPDATE commands SET status = ?1, result_json = ?2, completed_at = ?3, updated_at = ?3 WHERE id = ?4")
    .bind(success ? "completed" : "failed", JSON.stringify(result), now, commandId)
    .run();

  await recordEvent(env, pcId, "command-result", { commandId, ...result });
  if (!success) {
    await notifyCommandFailure(env, record.owner_id, pcId, record.type, error, payload);
  }
}

async function setPcStatus(env: Env, pcId: string, status: PcStatus, eventType: string): Promise<void> {
  const now = Date.now();
  await env.DEVICE_DB
    .prepare("UPDATE pc_devices SET status = ?1, last_seen_at = ?2, last_event_type = ?3, updated_at = ?2 WHERE id = ?4")
    .bind(status, now, eventType, pcId)
    .run();
}

async function setPcHeartbeat(env: Env, pcId: string, eventType = "heartbeat"): Promise<void> {
  const now = Date.now();
  await env.DEVICE_DB
    .prepare("UPDATE pc_devices SET last_seen_at = ?1, last_event_type = ?2, updated_at = ?1 WHERE id = ?3")
    .bind(now, eventType, pcId)
    .run();
}

async function recordEvent(env: Env, pcId: string, type: EventType | string, payload: Record<string, unknown>): Promise<void> {
  await env.DEVICE_DB
    .prepare("INSERT INTO events (id, pc_id, type, payload_json, created_at) VALUES (?1, ?2, ?3, ?4, ?5)")
    .bind(createId("evt"), pcId, type, JSON.stringify(payload), Date.now())
    .run();
}

async function notifyPcStatus(env: Env, pcId: string, notification: NotificationPayload): Promise<void> {
  const pc = await queryFirst<PcRecord>(env.DEVICE_DB, "SELECT * FROM pc_devices WHERE id = ?1", pcId);
  if (!pc?.owner_id) {
    return;
  }

  await notifyOwner(env, pc.owner_id, {
    ...notification,
    pcId,
    pcName: pc.name,
    data: {
      pcId: pcId,
      pcName: pc.name,
      status: pc.status,
      ...(notification.data ?? {}),
    },
  });
}

function formatNotificationSettings(settings: NotificationPreferencesRecord) {
  return {
    startupEnabled: Boolean(settings.startup_enabled),
    shutdownEnabled: Boolean(settings.shutdown_enabled),
    agentStopEnabled: Boolean(settings.agent_stop_enabled),
    sleepEnabled: Boolean(settings.sleep_enabled),
    wakeEnabled: Boolean(settings.wake_enabled),
    offlineEnabled: Boolean(settings.offline_enabled),
    commandFailedEnabled: Boolean(settings.command_failed_enabled),
    updatedAt: settings.updated_at,
  };
}

async function getNotificationSettings(env: Env, ownerId: string): Promise<NotificationPreferencesRecord> {
  const existing = await queryFirst<NotificationPreferencesRecord>(
    env.DEVICE_DB,
    "SELECT * FROM owner_notification_preferences WHERE owner_id = ?1",
    ownerId,
  );
  if (existing) {
    return existing;
  }

  return {
    owner_id: ownerId,
    startup_enabled: 1,
    shutdown_enabled: 1,
    agent_stop_enabled: 1,
    sleep_enabled: 1,
    wake_enabled: 1,
    offline_enabled: 1,
    command_failed_enabled: 1,
    updated_at: 0,
  };
}

function isNotificationEnabled(settings: NotificationPreferencesRecord, key: NotificationPreferenceKey): boolean {
  switch (key) {
    case "startup":
      return Boolean(settings.startup_enabled);
    case "shutdown":
      return Boolean(settings.shutdown_enabled);
    case "agentStop":
      return Boolean(settings.agent_stop_enabled);
    case "sleep":
      return Boolean(settings.sleep_enabled);
    case "wake":
      return Boolean(settings.wake_enabled);
    case "offline":
      return Boolean(settings.offline_enabled);
    case "commandFailed":
      return Boolean(settings.command_failed_enabled);
    default:
      return true;
  }
}

async function notifyOwner(env: Env, ownerId: string, notification: NotificationPayload): Promise<void> {
  try {
    if (notification.preferenceKey) {
      const settings = await getNotificationSettings(env, ownerId);
      if (!isNotificationEnabled(settings, notification.preferenceKey)) {
        return;
      }
    }

    const notificationType = notification.type ?? notification.data?.type ?? "system";
    if (notificationType === "offline" && notification.pcId) {
      const recentOffline = await env.DEVICE_DB
        .prepare(
          `SELECT id
           FROM owner_notifications
           WHERE owner_id = ?1
             AND pc_id = ?2
             AND type = 'offline'
             AND created_at >= ?3
           ORDER BY created_at DESC
           LIMIT 1`,
        )
        .bind(ownerId, notification.pcId, Date.now() - 45_000)
        .first<{ id: string | null }>();

      if (recentOffline?.id) {
        return;
      }
    }

    const notificationId = createId("ntf");
    const payloadJson = JSON.stringify({
      ...(notification.data ?? {}),
      pcId: notification.pcId ?? null,
      pcName: notification.pcName ?? null,
    });
    await env.DEVICE_DB
      .prepare(
        `INSERT INTO owner_notifications (id, owner_id, pc_id, type, title, body, payload_json, is_read, created_at, read_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 0, ?8, NULL)`,
      )
      .bind(
        notificationId,
        ownerId,
        notification.pcId ?? null,
        notificationType,
        notification.title,
        notification.body,
        payloadJson,
        Date.now(),
      )
      .run();

    await sendFcmToOwnerDevices(env, ownerId, {
      notification: {
        title: notification.title,
        body: notification.body,
      },
      data: {
        notificationId,
        type: notification.type ?? notification.data?.type ?? "system",
        ...(notification.pcId ? { pcId: notification.pcId } : {}),
        ...(notification.pcName ? { pcName: notification.pcName } : {}),
        ...(notification.data ?? {}),
      },
    });
  } catch (error) {
    console.warn("notifyOwner failed", coerceErrorMessage(error));
  }
}

async function notifyClipboardSync(env: Env, pcId: string, changedAt: number): Promise<void> {
  try {
    const pc = await queryFirst<PcRecord>(env.DEVICE_DB, "SELECT * FROM pc_devices WHERE id = ?1", pcId);
    if (!pc?.owner_id) {
      return;
    }

    await sendFcmToOwnerDevices(env, pc.owner_id, {
      data: {
        type: "clipboard-sync",
        pcId,
        pcName: pc.name,
        changedAt: String(changedAt || Date.now()),
      },
    });
  } catch (error) {
    console.warn("notifyClipboardSync failed", coerceErrorMessage(error));
  }
}

async function sendFcmToOwnerDevices(env: Env, ownerId: string, payload: FcmDispatchPayload): Promise<void> {
  try {
    if (!env.FIREBASE_PROJECT_ID || !env.FIREBASE_CLIENT_EMAIL || !env.FIREBASE_PRIVATE_KEY) {
      return;
    }

    const devices = await queryAll<{ fcm_token: string }>(
      env.DEVICE_DB,
      "SELECT fcm_token FROM mobile_devices WHERE owner_id = ?1",
      ownerId,
    );

    if (devices.length === 0) {
      return;
    }

    const accessToken = await getFirebaseAccessToken(env);
    const projectId = env.FIREBASE_PROJECT_ID;
    const sendUrl = `https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`;

    await Promise.allSettled(
      devices.map(async (device) => {
        const response = await fetch(sendUrl, {
          method: "POST",
          headers: {
            authorization: `Bearer ${accessToken}`,
            "content-type": "application/json",
          },
          body: JSON.stringify({
            message: {
              token: device.fcm_token,
              ...(payload.notification ? { notification: payload.notification } : {}),
              data: payload.data,
              android: {
                priority: "high",
              },
            },
          }),
        });

        if (!response.ok) {
          const details = await response.text();
          console.warn("FCM send failed", response.status, details);
        }
      }),
    );
  } catch (error) {
    console.warn("sendFcmToOwnerDevices failed", coerceErrorMessage(error));
  }
}

async function getFirebaseAccessToken(env: Env): Promise<string> {
  const nowSeconds = Math.floor(Date.now() / 1000);

  if (firebaseTokenCache && firebaseTokenCache.expiresAt > nowSeconds + 60) {
    return firebaseTokenCache.token;
  }

  const header = base64UrlEncode(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claimSet = base64UrlEncode(
    JSON.stringify({
      iss: env.FIREBASE_CLIENT_EMAIL,
      scope: "https://www.googleapis.com/auth/firebase.messaging",
      aud: "https://oauth2.googleapis.com/token",
      exp: nowSeconds + 3600,
      iat: nowSeconds,
    }),
  );
  const signingInput = `${header}.${claimSet}`;
  const signature = await signJwt(signingInput, env.FIREBASE_PRIVATE_KEY!);

  const body = new URLSearchParams({
    grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
    assertion: `${signingInput}.${signature}`,
  });

  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: body.toString(),
  });

  if (!response.ok) {
    throw new HttpError(500, `Unable to acquire Firebase access token (${response.status}).`);
  }

  const payload = (await response.json<{
    access_token: string;
    expires_in: number;
  }>()) ?? { access_token: "", expires_in: 0 };

  firebaseTokenCache = {
    token: payload.access_token,
    expiresAt: nowSeconds + payload.expires_in,
  };

  return payload.access_token;
}

async function signJwt(signingInput: string, pem: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToArrayBuffer(pem),
    {
      name: "RSASSA-PKCS1-v1_5",
      hash: "SHA-256",
    },
    false,
    ["sign"],
  );

  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(signingInput),
  );

  return base64UrlEncode(signature);
}

async function notifyCommandFailure(
  env: Env,
  ownerId: string,
  pcId: string,
  commandType: string,
  error: string | null | undefined,
  payload: Record<string, unknown>,
): Promise<void> {
  const pc = await queryFirst<PcRecord>(env.DEVICE_DB, "SELECT id, name, status FROM pc_devices WHERE id = ?1", pcId);
  await notifyOwner(env, ownerId, {
    title: "Komut basarisiz",
    body: `${pc?.name ?? "PC"} uzerindeki ${commandType} komutu tamamlanamadi.`,
    type: "command-failed",
    pcId,
    pcName: pc?.name ?? null,
    preferenceKey: "commandFailed",
    data: {
      type: "command-failed",
      pcId,
      pcName: pc?.name ?? "PC",
      commandType,
      error: error ?? "Bilinmeyen hata",
      payloadSummary: JSON.stringify(payload).slice(0, 500),
    },
  });
}

function getNotificationForEvent(eventType: EventType): NotificationPayload | null {
  switch (eventType) {
    case "startup":
      return { title: "PC acildi", body: "Bilgisayar acildi ve ajan hazir.", type: "startup", preferenceKey: "startup" };
    case "shutdown":
      return { title: "PC kapandi", body: "Bilgisayar kapandi.", type: "shutdown", preferenceKey: "shutdown" };
    case "agent-stop":
      return {
        title: "Ajan kapatildi",
        body: "Uzaktan kontrol ajani kapatildi.",
        type: "agent-stop",
        preferenceKey: "agentStop",
      };
    case "logoff":
      return {
        title: "Oturum kapatiliyor",
        body: "Windows oturumu kapatiliyor.",
        type: "logoff",
        preferenceKey: "shutdown",
      };
    case "sleep":
      return { title: "PC uykuya gecti", body: "Bilgisayar su an uyku modunda.", type: "sleep", preferenceKey: "sleep" };
    case "wake":
      return { title: "PC uyandi", body: "Bilgisayar tekrar aktif oldu.", type: "wake", preferenceKey: "wake" };
    default:
      return null;
  }
}

async function requireOwner(env: Env, request: Request): Promise<{ id: string }> {
  const token = getBearerToken(request);
  if (!token) {
    throw new HttpError(401, "Authorization header is missing.");
  }

  const owner = await findOwnerByToken(env, token);
  if (!owner) {
    throw new HttpError(401, "Owner token is invalid.");
  }

  return owner;
}

async function findOwnerByToken(env: Env, token: string | null | undefined): Promise<{ id: string } | null> {
  const normalizedToken = token?.trim();
  if (!normalizedToken) {
    return null;
  }

  const tokenHash = await sha256Hex(normalizedToken);
  return queryFirst<{ id: string }>(env.DEVICE_DB, "SELECT id FROM owners WHERE token_hash = ?1", tokenHash);
}

async function requireAgent(env: Env, request: Request): Promise<PcRecord> {
  const pcId = request.headers.get("x-pc-id")?.trim();
  const agentSecret = request.headers.get("x-agent-secret")?.trim();

  if (!pcId || !agentSecret) {
    throw new HttpError(401, "x-pc-id and x-agent-secret headers are required.");
  }

  const pc = await queryFirst<PcRecord>(env.DEVICE_DB, "SELECT * FROM pc_devices WHERE id = ?1", pcId);
  if (!pc || !(await constantTimeEqualsHash(pc.secret_hash, agentSecret))) {
    throw new HttpError(401, "Agent credentials are invalid.");
  }

  return pc;
}

function buildAgentWebSocketUrl(request: Request, pcId: string): string {
  const requestUrl = new URL(request.url);
  requestUrl.protocol = requestUrl.protocol === "https:" ? "wss:" : "ws:";
  requestUrl.pathname = "/api/pc/connect";
  requestUrl.search = "";
  requestUrl.hash = "";
  requestUrl.searchParams.set("pcId", pcId);
  return requestUrl.toString();
}

function buildAbsoluteUrl(request: Request, path: string): string {
  const url = new URL(request.url);
  url.protocol = request.url.startsWith("https:") ? "https:" : "http:";
  url.pathname = path;
  url.search = "";
  url.hash = "";
  return url.toString();
}

function getEffectivePcStatus(pc: Pick<PcRecord, "status" | "last_seen_at">): PcStatus {
  if (pc.status !== "online") {
    return pc.status;
  }

  return isPcRecentlySeen(pc.last_seen_at, ONLINE_STALE_AFTER_MS) ? "online" : "offline";
}

function isPcRecentlySeen(lastSeenAt: number | null, thresholdMs: number): boolean {
  return typeof lastSeenAt === "number" && lastSeenAt > 0 && Date.now() - lastSeenAt <= thresholdMs;
}

function getPairingState(pc: PcRecord, now: number, ttlMs: number): { code: string; expiresAt: number } {
  if (pc.pairing_code && pc.pairing_code_expires_at && pc.pairing_code_expires_at > now) {
    return { code: pc.pairing_code, expiresAt: pc.pairing_code_expires_at };
  }

  return { code: createPairingCode(), expiresAt: now + ttlMs };
}

function shouldSendOfflineNotification(
  previousPcState: Pick<PcRecord, "last_event_type" | "updated_at"> | null,
): boolean {
  if (!previousPcState?.last_event_type || !previousPcState.updated_at) {
    return true;
  }

  const gracefulEvents = new Set(["shutdown", "agent-stop", "logoff"]);
  if (!gracefulEvents.has(previousPcState.last_event_type)) {
    return true;
  }

  return Date.now() - previousPcState.updated_at > 15_000;
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function requireFileBucket(env: Env): R2Bucket {
  if (!env.FILE_BUCKET) {
    throw new HttpError(503, "R2 bucket binding is missing.");
  }

  return env.FILE_BUCKET;
}

function sanitizeFileName(value: string): string {
  const baseName = value.replace(/\\/g, "/").split("/").pop()?.trim() ?? "";
  const cleaned = baseName.replace(/[^a-zA-Z0-9._()\- ]+/g, "_").replace(/\s+/g, " ").trim();
  return cleaned || "file.bin";
}

function validateOwnerObjectKey(objectKey: string, ownerId: string): void {
  const prefix = `owners/${ownerId}/`;
  if (!objectKey.startsWith(prefix)) {
    throw new HttpError(403, "Object key is not accessible for this owner.");
  }
}

function validateAgentObjectKey(objectKey: string, ownerId: string, pcId: string): void {
  const prefix = `owners/${ownerId}/pc/${pcId}/transfers/`;
  if (!objectKey.startsWith(prefix)) {
    throw new HttpError(403, "Object key is not accessible for this PC.");
  }
}

async function queryFirst<T>(db: D1Database, sql: string, ...bindings: unknown[]): Promise<T | null> {
  const result = await db.prepare(sql).bind(...bindings).first<T>();
  return result ?? null;
}

async function queryAll<T>(db: D1Database, sql: string, ...bindings: unknown[]): Promise<T[]> {
  const result = await db.prepare(sql).bind(...bindings).all<T>();
  const queryError = (result as { error?: string | null }).error;
  if (queryError) {
    throw new HttpError(500, queryError);
  }

  return result.results ?? [];
}

async function readJson<T>(request: Request): Promise<T> {
  try {
    return (await request.json<T>()) as T;
  } catch {
    throw new HttpError(400, "Request body must be valid JSON.");
  }
}

function safeJsonParse<T>(value: string | null, fallback: T): T {
  if (!value) {
    return fallback;
  }

  try {
    return JSON.parse(value) as T;
  } catch {
    return fallback;
  }
}

function toInt(value: number | string | null | undefined): number {
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : 0;
  }

  if (typeof value === "string") {
    const parsed = Number.parseInt(value, 10);
    return Number.isFinite(parsed) ? parsed : 0;
  }

  return 0;
}

function getBearerToken(request: Request): string | null {
  const header = request.headers.get("authorization");
  if (!header?.startsWith("Bearer ")) {
    return null;
  }

  return header.slice("Bearer ".length).trim();
}

async function sha256Hex(value: string): Promise<string> {
  const data = new TextEncoder().encode(value);
  const hash = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(hash))
    .map((item) => item.toString(16).padStart(2, "0"))
    .join("");
}

async function constantTimeEqualsHash(expectedHash: string, rawValue: string): Promise<boolean> {
  const actualHash = await sha256Hex(rawValue);
  if (expectedHash.length !== actualHash.length) {
    return false;
  }

  let diff = 0;
  for (let i = 0; i < expectedHash.length; i += 1) {
    diff |= expectedHash.charCodeAt(i) ^ actualHash.charCodeAt(i);
  }

  return diff === 0;
}

function createPairingCode(length = 6): string {
  const alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (item) => alphabet[item % alphabet.length]).join("");
}

function createSecret(byteLength = 32): string {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return base64UrlEncode(bytes);
}

function createId(prefix: string): string {
  return `${prefix}_${crypto.randomUUID().replace(/-/g, "")}`;
}

function ensureString(value: string | undefined, field: string): void {
  if (!value || !value.trim()) {
    throw new HttpError(400, `${field} is required.`);
  }
}

function base64UrlEncode(value: string | ArrayBuffer | Uint8Array): string {
  let bytes: Uint8Array;
  if (typeof value === "string") {
    bytes = new TextEncoder().encode(value);
  } else if (value instanceof Uint8Array) {
    bytes = value;
  } else {
    bytes = new Uint8Array(value);
  }

  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }

  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function pemToArrayBuffer(pem: string): ArrayBuffer {
  const normalized = pem.replace(/\\n/g, "\n");
  const base64 = normalized
    .replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "")
    .replace(/\s+/g, "");
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);

  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }

  return bytes.buffer;
}

function json(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload, null, 2), { status, headers: JSON_HEADERS });
}

async function ensureDatabaseSchema(env: Env): Promise<void> {
  if (!schemaInitPromise) {
    schemaInitPromise = applyDatabaseSchema(env).catch((error) => {
      schemaInitPromise = null;
      throw error;
    });
  }

  await schemaInitPromise;
}

async function applyDatabaseSchema(env: Env): Promise<void> {
  await env.DEVICE_DB.batch([
    env.DEVICE_DB.prepare(
      `CREATE TABLE IF NOT EXISTS owners (
        id TEXT PRIMARY KEY,
        token_hash TEXT NOT NULL UNIQUE,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
      )`,
    ),
    env.DEVICE_DB.prepare(
      `CREATE TABLE IF NOT EXISTS pc_devices (
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
      )`,
    ),
    env.DEVICE_DB.prepare("CREATE INDEX IF NOT EXISTS idx_pc_devices_owner_id ON pc_devices (owner_id)"),
    env.DEVICE_DB.prepare("CREATE INDEX IF NOT EXISTS idx_pc_devices_pairing_code ON pc_devices (pairing_code)"),
    env.DEVICE_DB.prepare(
      `CREATE TABLE IF NOT EXISTS mobile_devices (
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
      )`,
    ),
    env.DEVICE_DB.prepare("CREATE INDEX IF NOT EXISTS idx_mobile_devices_owner_id ON mobile_devices (owner_id)"),
    env.DEVICE_DB.prepare(
      `CREATE TABLE IF NOT EXISTS commands (
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
      )`,
    ),
    env.DEVICE_DB.prepare("CREATE INDEX IF NOT EXISTS idx_commands_pc_status_created ON commands (pc_id, status, created_at)"),
    env.DEVICE_DB.prepare(
      `CREATE TABLE IF NOT EXISTS events (
        id TEXT PRIMARY KEY,
        pc_id TEXT NOT NULL,
        type TEXT NOT NULL,
        payload_json TEXT,
        created_at INTEGER NOT NULL,
        FOREIGN KEY (pc_id) REFERENCES pc_devices(id)
      )`,
    ),
    env.DEVICE_DB.prepare("CREATE INDEX IF NOT EXISTS idx_events_pc_created ON events (pc_id, created_at DESC)"),
    env.DEVICE_DB.prepare(
      `CREATE TABLE IF NOT EXISTS owner_notification_preferences (
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
      )`,
    ),
    env.DEVICE_DB.prepare(
      `CREATE TABLE IF NOT EXISTS owner_notifications (
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
      )`,
    ),
    env.DEVICE_DB.prepare(
      "CREATE INDEX IF NOT EXISTS idx_owner_notifications_owner_created ON owner_notifications (owner_id, created_at DESC)",
    ),
    env.DEVICE_DB.prepare(
      "CREATE INDEX IF NOT EXISTS idx_owner_notifications_owner_read ON owner_notifications (owner_id, is_read, created_at DESC)",
    ),
  ]);
}

function toErrorResponse(error: unknown): Response {
  if (error instanceof HttpError) {
    return json({ ok: false, error: error.message }, error.status);
  }

  const message = error instanceof Error ? error.message : "Unknown error";
  return json({ ok: false, error: message }, 500);
}

function coerceErrorMessage(error: unknown): string {
  if (error instanceof HttpError) {
    return error.message;
  }

  if (error instanceof Error) {
    return error.message;
  }

  return "Unknown internal error.";
}

class HttpError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "HttpError";
  }
}
