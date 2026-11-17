#!/usr/bin/env python3
"""Inserta proyecciones demo en report_request_projection (pasado + actual).

Uso en EC2 apps (con wallet montado o TNS_ADMIN):
  ORACLE_USER=... ORACLE_PASSWORD=... ORACLE_DSN=barriodig_low \\
  TNS_ADMIN=/home/ec2-user/barriodigital/wallet python3 seed-demo-projection.py

No borra filas existentes. Prefijo de folio: BD-2099- (demo).
"""

from __future__ import annotations

import os
import sys
import uuid
from datetime import datetime, timedelta, timezone

try:
    import oracledb
except ImportError:
    sys.stderr.write("Falta oracledb. Instala: pip3 install --user oracledb\n")
    sys.exit(1)

USER = os.environ["ORACLE_USER"]
PASSWORD = os.environ["ORACLE_PASSWORD"]
DSN = os.environ.get("ORACLE_DSN", "barriodig_low")
NOW = datetime.now(timezone.utc)

TYPES = [
    ("bache", "Bache en calzada"),
    ("alumbrado", "Alumbrado público"),
    ("agua", "Corte de agua"),
    ("basura", "Retiro de basura"),
    ("ruido", "Ruido molesto"),
    ("poda", "Poda de árboles"),
]
STATUSES_OPEN = ["INGRESADO", "ADMITIDO", "EN_GESTION", "EN_TERRENO"]
STATUSES_CLOSED = ["RESUELTO", "RECHAZADO"]
SECTORS = ["Centro", "Norte", "Sur", "Ralco", "Pitril", None]


def rows() -> list[tuple]:
    out: list[tuple] = []
    folio = 900000
    # Últimas 24 h (hora a hora)
    for h in range(24):
        created = NOW - timedelta(hours=h, minutes=(h * 7) % 50)
        proc = TYPES[h % len(TYPES)]
        status = STATUSES_OPEN[h % len(STATUSES_OPEN)] if h % 5 else "RESUELTO"
        closed = created + timedelta(hours=2 + (h % 5)) if status in STATUSES_CLOSED or status == "RESUELTO" else None
        if status == "RESUELTO" or status in STATUSES_CLOSED:
            status = STATUSES_CLOSED[h % 2]
            closed = created + timedelta(hours=1 + (h % 8))
        folio += 1
        out.append(make(folio, proc, status, created, closed, h))
    # Últimos 14 días
    for d in range(1, 15):
        for n in range(8):
            created = NOW - timedelta(days=d, hours=n * 2, minutes=n * 3)
            proc = TYPES[(d + n) % len(TYPES)]
            closed_flag = (d + n) % 3 == 0
            status = "RESUELTO" if closed_flag else STATUSES_OPEN[(d + n) % len(STATUSES_OPEN)]
            closed = created + timedelta(days=1, hours=n) if closed_flag else None
            if status == "RECHAZADO":
                closed = created + timedelta(hours=6)
            folio += 1
            out.append(make(folio, proc, status, created, closed, d + n))
    # Mes anterior (tendencia)
    for d in range(15, 40):
        for n in range(3):
            created = NOW - timedelta(days=d, hours=n * 4)
            proc = TYPES[(d * n) % len(TYPES)]
            status = "RESUELTO" if n % 2 == 0 else "EN_GESTION"
            closed = created + timedelta(days=2) if status == "RESUELTO" else None
            folio += 1
            out.append(make(folio, proc, status, created, closed, d))
    return out


def make(folio: int, proc: tuple[str, str], status: str, created: datetime, closed: datetime | None, salt: int):
    request_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"demo-report-{folio}"))
    folio_s = f"BD-2099-{folio:06d}"
    sector = SECTORS[salt % len(SECTORS)]
    lat = -37.47 + (salt % 20) * 0.01 if sector else None
    lon = -72.35 + (salt % 15) * 0.01 if sector else None
    assignee_id = None
    assignee_name = None
    if status in ("EN_GESTION", "EN_TERRENO", "RESUELTO") and salt % 2 == 0:
        assignee_id = "func-demo-1"
        assignee_name = "Funcionario Demo"
    due = (created + timedelta(days=5)).date()
    due_soon = due - timedelta(days=1)
    if closed is None:
        sla = "VENCIDO" if salt % 4 == 0 else ("POR_VENCER" if salt % 5 == 0 else "OK")
    else:
        sla = "VENCIDO" if salt % 3 == 0 else "OK"
    return (
        request_id,
        folio_s,
        proc[0],
        proc[1],
        sector,
        lat,
        lon,
        status,
        1,
        created,
        5,
        due,
        due_soon,
        sla,
        closed,
        assignee_id,
        assignee_name,
        closed or created,
    )


def main() -> None:
    rows_data = rows()
    print(f"Insertando {len(rows_data)} proyecciones demo…", flush=True)
    wallet = os.environ.get("TNS_ADMIN") or os.environ.get("ORACLE_WALLET_DIR")
    connect_kwargs = {"user": USER, "password": PASSWORD, "dsn": DSN}
    if wallet:
        connect_kwargs["config_dir"] = wallet
        connect_kwargs["wallet_location"] = wallet
    conn = oracledb.connect(**connect_kwargs)
    sql = """
        INSERT INTO report_request_projection (
          request_id, folio, procedure_type, procedure_label, sector, latitude, longitude,
          status, last_sequence, created_at, sla_business_days, due_date, due_soon_date,
          sla_status, closed_at, assignee_id, assignee_name, last_event_at
        ) VALUES (
          :1, :2, :3, :4, :5, :6, :7,
          :8, :9, :10, :11, :12, :13,
          :14, :15, :16, :17, :18
        )
    """
    inserted = 0
    skipped = 0
    with conn.cursor() as cur:
        for row in rows_data:
            try:
                cur.execute(sql, row)
                inserted += 1
            except oracledb.IntegrityError:
                skipped += 1
        conn.commit()
    conn.close()
    print(f"Listo: {inserted} insertadas, {skipped} ya existían.", flush=True)


if __name__ == "__main__":
    main()
