# Synthetic Cybersecurity Investigation Dataset — Server Crash

**Primary file:** `server_crash_logs.jsonl` — 195 chronological JSONL events  
**Timeline:** `2026-08-28 06:00:00` → `2026-08-28 10:35:09` (≈4.5 hours)  
**Server:** `APP-SRV-01` (`customer-portal-01`, `10.10.1.20`) — Linux, Production

This dataset is purpose-built for testing a **Neo4j + Cypher + local LLM** investigation platform. It tells one internally consistent story — normal baseline → reconnaissance → brute-force → compromise → persistence → resource exhaustion → application/DB failure → crash — that can be reconstructed by correlating events and building graph relationships. The root cause is never explicitly labeled; it must be inferred.

---

## 1. Fictional Environment

| Entity | Value | Description |
|---|---|---|
| **Server** | `APP-SRV-01` | Production application server |
| **Hostname** | `customer-portal-01` | Linux (Ubuntu 22.04 LTS, kernel 5.15.0-91-generic) |
| **Private IP** | `10.10.1.20` | Internal VPC address |
| **Application** | `CustomerPortal` v2.8.4 (port 8080) | Customer-facing web app |
| **Database** | `CustomerDB` PostgreSQL 14.9 (port 5432) | Backing database |
| **Monitoring** | `10.10.1.30` | Internal monitoring server (health checks) |
| **Backup** | `10.10.1.15` | Backup server |
| **DNS** | `8.8.8.8` | External DNS |
| **Internal users** | `admin` (10.10.1.10), `alice` (10.10.1.45), `bob` (10.10.1.52), `service_backup` (10.10.1.15), `deploy_user` (10.10.1.100), `monitoring` (10.10.1.30) | Legitimate accounts |
| **External attacker IP** | `185.22.10.5` | Fictional external actor — appears repeatedly |
| **Suspicious process** | `update_helper` (`/tmp/update_helper`, PIDs 3421/3422/3428) | Never labeled "malware" in logs |
| **Key files** | `/tmp/update_helper`, `/tmp/.cache_update`, `/tmp/.cache_update.2`, `/tmp/tmp_3421.log`, `/etc/app/config.conf`, `/etc/crontab`, `/var/log/customer-portal.log`, `/backup/CustomerDB_20260828_0645.dump` |  |

### Normal Operating Ranges (Baseline)

- **CPU:** 20–55% (occasional 45% during backup), load avg 0.5–0.85, 4 cores
- **RAM:** 35–65% (3360–4259 MB of 8192 MB)
- **Disk:** 58–59% used (116–118 GB of 200 GB), I/O <1100 KB/s
- **Network:** 3–6 Mbps, 34–42 open connections, bytes_in ~180–235 KB, bytes_out ~210–278 KB
- **Application:** HTTP 200, response 35–145 ms, `/health` 36–45 ms
- **Database:** SELECT 12–22 ms, pool active 2–5, idle available

These baselines are established in the first ~70 events (06:00–07:35) so anomalies are detectable by comparison.

---

## 2. Incident Timeline (16 Stages)

All timestamps are `2026-08-28` (UTC). Events are strictly chronological with realistic spacing (30–90 s baseline, 1–25 s during attack bursts, then gradual metric escalation).

| Phase | Time Window | Stage | Key Events | Event IDs |
|---|---|---|---|---|
| **0** | 06:00:00–06:01 | Boot | Linux boot, CustomerPortal & CustomerDB start | EVT-0001–0003 |
| **1** | 06:01–07:50 | **Normal activity** (≈55% of dataset) | CPU/RAM/disk/network metrics, alice/bob/admin logins, API 200s, DB queries, pg_dump backup, health checks healthy, occasional harmless noise (bob typo login, 404 for /api/customers/999) | EVT-0004–0062 |
| **2** | 08:09:08–08:12 | **Reconnaissance / Port Scan** | Attacker `185.22.10.5` SYN scans 6 ports (22,80,443,8080,3306,5432) → IDS `port_scan` alert → establishes TCP 22 SYN-ACK | EVT-0063–0069, EVT-0073 |
| **3** | 08:13–08:37 | **Repeated Failed Auth** | 11 failed `admin` logins from attacker + 2 failed `alice` probes + `brute_force` IDS alert; interleaved normal API/CPU noise | EVT-0074–0094 |
| **4** | 08:39:44 | **Successful Suspicious Auth** | `admin` from `185.22.10.5` succeeds after 11 failures (`anomaly: login_after_bruteforce`), session `sess-x1y2z3` created | EVT-0095–0097 |
| **5** | 08:48:15–08:50 | **Suspicious Process Execution** | `update_helper --daemon` (pid 3421, ppid 1845, parent sshd, user admin) + child `--worker --threads 4` (3422) | EVT-0102–0103 |
| **6** | 08:56–09:01 | **File Creation/Modification** | `/tmp/update_helper` (1.8 MB, 755), `/etc/app/config.conf` hash change, `/tmp/.cache_update`, `/tmp/tmp_3421.log` | EVT-0108–0112 |
| **7** | 09:03–09:09 | **Configuration Changes** | `config.conf` max_connections 100→500, added `outbound_allow 185.22.10.5:443`, `/etc/crontab` persistence `*/5 * * * * /tmp/update_helper --daemon` | EVT-0114–0117 |
| **8** | 09:12–09:20 | **Outbound Connections** | `10.10.1.20 → 185.22.10.5:443` ×3 (1.2 MB, 0.89 MB, 2.1 MB), bandwidth 18.4 Mbps, normal connection to backup server interleaved | EVT-0120–0126 |
| **9–10** | 09:24–09:40 | **CPU & RAM Escalation (gradual)** | CPU 52→58→62→68→72→78→84→88→91→93→95% (top_process `update_helper` 38→84%), RAM 54→61→67→72→78→83→86→89% | EVT-0104, 0113, 0119, 0123, 0127–0146 |
| **11** | 09:42–09:50 | **Disk/Network Surge** | Disk 66→69→72% , I/O 3890/3420→5890/5230 KB/s, `/tmp/.cache_update.2` 45 MB, network 48→62 Mbps, 85→98 open conns | EVT-0148–0151 |
| **12** | 09:53–10:09 | **Application Errors** | API 500s (1245 ms) → Timeout → Health 500 → ConnectionPool exhausted (100/0) → Gateway 504, health degraded | EVT-0152–0165 |
| **13** | 10:11–10:23 | **Database Problems** | DB slow queries 3420→5800 ms, pool 98/2/15 → 100/0/58 waiting, `too many connections`, `CustomerDB unavailable` | EVT-0166–0178 |
| **14** | 10:26–10:31 | **Service Degradation** | `CustomerPortal` degraded, worker threads 50/50, CPU 97–98%, RAM 93–95%, DB pool 58 waiting | EVT-0179–0187 |
| **15** | 10:32–10:33 | **Unresponsive** | API 504 10000 ms, OOM `Java heap space`, service `failing` OOMKilled (137) | EVT-0188–0190 |
| **16** | 10:34:51–10:35:09 | **Final Crash** | `10:34:51 SERVICE_FAILURE` → `10:34:58 HEALTH_CHECK_FAILED (Connection refused)` → `10:35:02 SERVER_UNRESPONSIVE` → `10:35:07 SERVER_CRASH (Kernel panic - Out of memory)` → `10:35:09 NETWORK_CONNECTION CLOSED` | EVT-0191–0195 |

> **Hidden chain (to be discovered):** `185.22.10.5` → port_scan → brute force → `admin` login → `update_helper` → `/tmp/update_helper` + `config.conf`/`crontab` → outbound 443 → CPU/RAM/disk surge → latency 890 ms → 500/504 → DB pool exhaustion → OOM → crash.

---

## 3. Major Entities (for Neo4j Nodes)

- **Server:** `APP-SRV-01` (all 195 events)
- **Host:** `customer-portal-01`
- **IPs:** `10.10.1.20` (server), `185.22.10.5` (attacker, 32 events), `10.10.1.45` (alice), `10.10.1.52` (bob), `10.10.1.10` (admin internal), `10.10.1.15` (backup), `10.10.1.30` (monitoring), `10.10.1.100` (deploy_user), `8.8.8.8` (DNS)
- **Users:** `admin` (compromised), `alice`, `bob`, `service_backup`, `deploy_user`, `monitoring`
- **Processes:** `update_helper` (suspicious, PIDs 3421,3422,3428), `app_worker` (1842), `pg_dump` (2105), `monitoring_agent` (1920)
- **Files:** `/tmp/update_helper`, `/tmp/.cache_update`, `/tmp/.cache_update.2`, `/tmp/tmp_3421.log`, `/etc/app/config.conf`, `/etc/crontab`, `/var/log/customer-portal.log`
- **Application:** `CustomerPortal`
- **Database:** `CustomerDB`
- **Sessions:** `sess-x1y2z3` (attacker), plus normal `sess-a1b2c3`, `sess-d4e5f6`, `sess-g7h8i9`, etc.

---

## 4. Important Event Types

| Event Type | Count | Severity Typical | Purpose |
|---|---|---|---|
| `CPU_METRIC` | 27 | INFO→CRITICAL | Time-series CPU 28→98%, load avg, top_process |
| `RAM_METRIC` | 19 | INFO→CRITICAL | RAM 41→95% |
| `DISK_METRIC` | 10 | INFO→CRITICAL | Disk % + I/O |
| `NETWORK_METRIC` | 9 | INFO→CRITICAL | Bandwidth, bytes, open conns |
| `DB_METRIC` | 3 | WARN→CRITICAL | Pool exhaustion |
| `AUTH_LOGIN` | 21 | INFO/WARN | 11 attacker failures + success, normal logins, 1 harmless bob typo |
| `SESSION_CREATE` | 1 | INFO | Attacker session |
| `AUTH_LOGOUT` | 1 | INFO | Normal |
| `API_REQUEST` | 22 | INFO→ERROR | 200s baseline, then 500/503/504 surge |
| `DB_QUERY` | 11 | INFO→ERROR | Normal SELECTs → timeouts |
| `APP_ERROR` | 6 | WARN→CRITICAL | Timeout, ConnectionPool, Database, OOM |
| `HEALTH_CHECK` | 14 | INFO→CRITICAL | Healthy → degraded → failure |
| `NETWORK_CONNECTION` | 18 | INFO/WARN/CRITICAL | 6 scan BLOCKED, 1 SYN-ACK, 6 outbound to attacker, normal internal |
| `PROCESS_START` | 6 | INFO/WARN | app_worker, pg_dump, update_helper ×3 |
| `PROCESS_STOP` | 1 | INFO | pg_dump |
| `FILE_CREATE` | 5 | INFO/WARN | 3 suspicious + 2 normal |
| `FILE_MODIFY` | 4 | INFO/WARN | config.conf + crontab + normal log |
| `CONFIG_CHANGE` | 2 | WARN | config.conf persistence |
| `CONFIG_CHECK` | 1 | INFO | Routine check (noise) |
| `SCHEDULED_JOB` | 2 | INFO/WARN | temp_cleanup + malicious persistence |
| `BACKUP_JOB` | 2 | INFO | Normal backups |
| `IDS_ALERT` | 2 | WARN | port_scan, brute_force |
| `SERVICE_START` | 2 | INFO | Portal + DB boot |
| `SERVICE_STATUS` | 2 | WARN/ERROR | degraded → failing |
| `SERVICE_FAILURE` | 1 | CRITICAL | 10:34:51 |
| `SERVER_STATUS` | 1 | CRITICAL | 10:35:02 unresponsive |
| `SERVER_CRASH` | 1 | CRITICAL | 10:35:07 kernel panic |
| `SYSTEM_BOOT` | 1 | INFO | 06:00:00 |

Total **195** events. Severity: INFO 96 (49.2%), WARN 65, CRITICAL 22, ERROR 12. Baseline normal ≈96 INFO + ~9 harmless WARN (404, typo recovery, config check) = 105 (53.8%) — meets 50–60% requirement.

---

## 5. Intended Hidden Attack Chain (Answer Key)

```
185.22.10.5 ──port_scan(08:09)──▶ APP-SRV-01
   │ brute_force 11× admin (08:13–08:37) ──IDS alert──▶
   │ SUCCESS login admin sess-x1y2z3 (08:39:44)
   └─▶ PROCESS_START update_helper (08:48, pid 3421, parent sshd)
         ├─▶ FILE_CREATE /tmp/update_helper (08:56)
         ├─▶ FILE_MODIFY /etc/app/config.conf (08:57 hash a3f5→7d9e)
         ├─▶ CONFIG_CHANGE max_connections 500 + outbound_allow 185.22.10.5:443 (09:03)
         ├─▶ FILE_MODIFY /etc/crontab + SCHEDULED_JOB persistence (09:06)
         └─▶ NETWORK_CONNECTION outbound 443 ×6 (09:12–10:28, up to 6.2 MB)
               └─▶ RESOURCE SPIKE CPU 52→98% RAM 54→95% Disk 60→76% Net 18→71 Mbps
                     └─▶ APP latency 890 ms → 500/Timeout/PoolExhausted
                           └─▶ DB pool 100/0/58, queries 5800 ms
                                 └─▶ SERVICE_FAILURE 10:34:51 → HEALTH_CHECK_FAILED 10:34:58
                                       └─▶ SERVER_UNRESPONSIVE 10:35:02 → SERVER_CRASH 10:35:07 (OOM, last_process update_helper)
```

> Investigator must correlate: **IP → AUTH_LOGIN → PROCESS_START → FILE_CREATE/MODIFY → NETWORK_CONNECTION → CPU/RAM → APP_ERROR → DB_METRIC → crash**.

---

## 6. Noise vs. Signal

**Signal (attack-related, ~85 events):** all `185.22.10.5` events (32), `update_helper` processes/files, config/crontab changes, outbound 443, IDS alerts, CPU/RAM/disk/net WARN→CRITICAL after 08:48, APP 500/504, DB pool exhaustion, final crash chain.

**Noise (normal, ~105–110 events):** CPU/RAM 20–55% metrics (06:00–07:50), normal alice/bob/deploy_user/service_backup logins, API 200s, DB SELECT 12–28 ms, `pg_dump` backup, `session_cleanup`/`temp_cleanup`, `monitoring_agent`, `CONFIG_CHECK`, internal connections (10.10.1.15:22, 10.10.1.30:9090, 8.8.8.8:53), harmless bob typo + immediate success, 404 for /api/customers/999.

The noise ensures simple string search for `"malicious": true` fails; correlation across time windows is required.

---

## 7. Final Server Crash (Authoritative)

```
2026-08-28T10:33:57 SERVICE_STATUS CustomerPortal failing OOMKilled (137)
2026-08-28T10:34:51 SERVICE_FAILURE CustomerPortal pid 1842 terminated unexpectedly
2026-08-28T10:34:58 HEALTH_CHECK  CRITICAL /health Connection refused (15000 ms)
2026-08-28T10:35:02 SERVER_STATUS CRITICAL unresponsive last_cpu 99 last_ram 96 load 6.20
2026-08-28T10:35:07 SERVER_CRASH  CRITICAL Kernel panic - Out of memory and no killable processes last_process update_helper
2026-08-28T10:35:09 NETWORK_CONNECTION CLOSED 10.10.1.20:52346 → 185.22.10.5:443 (update_helper)
```

Root cause **not stated** — implied: `update_helper` persisted via crontab, consumed CPU/RAM/disk, exhausted DB pool, triggered OOM.

---

## 8. Data Format & Usage

Each line is one JSON object (JSONL). Common fields:

```json
{
  "event_id": "EVT-0095",
  "timestamp": "2026-08-28T08:39:44",
  "event_type": "AUTH_LOGIN",
  "severity": "WARN",
  "server_id": "APP-SRV-01",
  "hostname": "customer-portal-01",
  "source": "auth_service",
  "user": "admin",
  "source_ip": "185.22.10.5",
  "status": "SUCCESS",
  "details": {"auth_method":"password","session_id":"sess-x1y2z3","previous_failed_attempts":11}
}
```

Fields are sparse: `source_ip`/`destination_ip` only for network/auth, `process`/`pid`/`parent_pid` only for process/file, `file_path` for file/config, `application`/`database` for app/db.

### Neo4j Import Hints

- Nodes: `Server`, `Host`, `IP`, `User`, `Session`, `Process`, `File`, `Application`, `Database`, `Event`
- Relationships derivable:
  - `(:User)-[:LOGGED_INTO]->(:Server)` from `AUTH_LOGIN SUCCESS`
  - `(:IP)-[:CONNECTED_TO]->(:Server)` from `NETWORK_CONNECTION`
  - `(:IP)-[:ATTEMPTED_LOGIN {status}]->(:User)`
  - `(:Server)-[:RUNS]->(:Process)` / `(:Process)-[:CHILD_OF]->(:Process)` via `pid`/`parent_pid`
  - `(:Process)-[:CONNECTED_TO]->(:IP)` / `(:Process)-[:MODIFIED]->(:File)` / `(:User)-[:MODIFIED]->(:File)`
  - `(:Server)-[:RUNS]->(:Application)-[:USES]->(:Database)`
  - `(:Server)-[:EXPERIENCED]->(:Anomaly {cpu, ram})`
  - `(:Process)-[:CONTRIBUTED_TO]->(:ResourceSpike)` (via `top_process`)

### Example Cypher Queries (Practice)

```cypher
// All events for server
MATCH (e:Event {server_id:"APP-SRV-01"}) RETURN e ORDER BY e.timestamp;

// All IPs connected to server
MATCH (e:Event {server_id:"APP-SRV-01"}) WHERE e.source_ip IS NOT NULL
RETURN DISTINCT e.source_ip, count(*) ORDER BY count(*) DESC;

// Failed and successful logins from suspicious IP
MATCH (e:Event) WHERE e.source_ip="185.22.10.5" AND e.event_type="AUTH_LOGIN"
RETURN e.timestamp, e.user, e.status ORDER BY e.timestamp;

// Processes started after suspicious login
MATCH (login:Event {event_type:"AUTH_LOGIN", source_ip:"185.22.10.5", status:"SUCCESS"})
WITH login.timestamp AS t
MATCH (p:Event {event_type:"PROCESS_START"}) WHERE p.timestamp > t AND p.process="update_helper"
RETURN p;

// Files modified by user/process
MATCH (e:Event) WHERE e.file_path IS NOT NULL AND e.user="admin"
RETURN e.timestamp, e.file_path, e.process ORDER BY e.timestamp;

// CPU/RAM anomalies before crash
MATCH (e:Event) WHERE e.event_type IN ["CPU_METRIC","RAM_METRIC"] AND e.timestamp > "2026-08-28T09:00:00"
RETURN e.timestamp, e.event_type, e.details ORDER BY e.timestamp;

// Events within 30 min before crash
MATCH (e:Event) WHERE e.timestamp >= "2026-08-28T10:05:07" AND e.timestamp <= "2026-08-28T10:35:07"
RETURN e ORDER BY e.timestamp;

// Path external IP → crash (requires graph model)
// MATCH path = shortestPath((ip:IP {address:"185.22.10.5"})-[:CONNECTED_TO|ATTEMPTED_LOGIN|STARTED_PROCESS*1..5]->(crash:Event {event_type:"SERVER_CRASH"}))
```

---

## 9. Generation Notes & Constraints Met

- **Fictional & safe:** No real PII, credentials, or victims. Attacker IP `185.22.10.5` is RFC-non-routable documentation range, not attributed.
- **Not explicitly labeled:** `update_helper` never tagged `malware`; crash reason is kernel OOM, not “attack succeeded”.
- **No random disconnected events:** Every event fits the staged narrative; metrics evolve gradually (CPU 28→98% in 2.5 h, no 30→99% jump).
- **Entity consistency:** `APP-SRV-01`, `185.22.10.5`, `admin`, `update_helper`, `CustomerPortal`, `CustomerDB` appear identically throughout.
- **Investigation-friendly:** 18 network connections to attacker, 21 auth logins (11 failures → success), 9 file changes post-login provide multi-hop paths.
- **Realistic spacing:** 30–90 s baseline, 1–3 s port scan burst, 15–60 s brute-force, then metric polling every 30–60 s with escalating values.

---

## 10. Files

- `server_crash_logs.jsonl` — 195 lines, UTF-8, one JSON per line
- `README.md` — this file
- `expected_entities.json` — machine-readable entity inventory for extraction-pipeline validation

**Validate:** `cat server_crash_logs.jsonl | wc -l` → 195; `jq -s 'sort_by(.timestamp)'` should be sorted.

---

*Generated for local testing only — do not deploy to production.*
