import sqlite3
from pathlib import Path
from uuid import uuid4
import os

from fastapi import FastAPI, File, Header, HTTPException, UploadFile
from fastapi.responses import HTMLResponse
from fastapi.staticfiles import StaticFiles

BASE_DIR = Path(__file__).resolve().parent.parent
UPLOAD_DIR = BASE_DIR / "received_files"
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)

DB_PATH = UPLOAD_DIR / "transfer_receipts.sqlite3"


def init_transfer_db():
    with sqlite3.connect(DB_PATH) as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS transfer_receipts (
                transfer_id TEXT PRIMARY KEY,
                filename TEXT NOT NULL,
                stored_as TEXT NOT NULL,
                size_bytes INTEGER NOT NULL
            )
        """)


init_transfer_db()

# Atur token melalui environment variable sebelum menjalankan server.
API_TOKEN = os.environ.get("REMOTE_CAMERA_TOKEN")

app = FastAPI(title="Remote Camera Receiver")

ALLOWED_EXTENSIONS = {
    ".jpg", ".jpeg", ".png", ".webp",
    ".mp4", ".mov", ".mkv", ".webm",
}

MAX_FILE_SIZE = 2 * 1024 * 1024 * 1024  # 2 GB


@app.get("/", response_class=HTMLResponse)
async def home():
    return """
    <!DOCTYPE html>
    <html lang="id">
    <head>
      <meta charset="UTF-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>Remote Camera Receiver</title>
      <style>
        body {
          font-family: Arial, sans-serif;
          max-width: 720px;
          margin: 60px auto;
          padding: 20px;
          background: #f5f5f5;
          color: #222;
        }
        main {
          background: white;
          padding: 28px;
          border-radius: 12px;
        }
        a { color: #1769aa; }
      </style>
    </head>
    <body>
      <main>
        <h1>Remote Camera Receiver</h1>
        <p>Server penerima foto dan video berjalan.</p>
        <p>Folder penyimpanan: <code>received_files</code></p>
        <p>Dokumentasi API: <a href="/docs">Buka /docs</a></p>
      </main>
    </body>
    </html>
    """


@app.get("/health")
async def health():
    return {"status": "online"}


@app.post("/upload")
async def upload_file(
    file: UploadFile = File(...),
    authorization: str | None = Header(default=None),
    transfer_id: str | None = Header(default=None, alias="X-File-ID"),
):
    if not API_TOKEN:
        raise HTTPException(
            status_code=503,
            detail="REMOTE_CAMERA_TOKEN belum dikonfigurasi.",
        )

    if authorization != f"Bearer {API_TOKEN}":
        raise HTTPException(status_code=401, detail="Token tidak valid.")

    original_name = Path(file.filename or "file").name
    extension = Path(original_name).suffix.lower()

    if extension not in ALLOWED_EXTENSIONS:
        raise HTTPException(
            status_code=400,
            detail="Format file tidak didukung.",
        )

    try:
        # Jika transfer ini sudah selesai, kembalikan hasil sebelumnya.
        if transfer_id:
            with sqlite3.connect(DB_PATH) as conn:
                row = conn.execute(
                    """
                    SELECT filename, stored_as, size_bytes
                    FROM transfer_receipts
                    WHERE transfer_id = ?
                    """,
                    (transfer_id,),
                ).fetchone()

            if row:
                return {
                    "status": "success",
                    "filename": row[0],
                    "stored_as": row[1],
                    "size_bytes": row[2],
                    "duplicate": True,
                }

        safe_name = f"{uuid4().hex}{extension}"
        destination = UPLOAD_DIR / safe_name
        size = 0

        try:
            with destination.open("xb") as output:
                while chunk := await file.read(1024 * 1024):
                    size += len(chunk)

                    if size > MAX_FILE_SIZE:
                        raise HTTPException(
                            status_code=413,
                            detail="Ukuran file melebihi batas 2 GB.",
                        )

                    output.write(chunk)

            if transfer_id:
                with sqlite3.connect(DB_PATH) as conn:
                    conn.execute(
                        """
                        INSERT INTO transfer_receipts
                        (transfer_id, filename, stored_as, size_bytes)
                        VALUES (?, ?, ?, ?)
                        """,
                        (transfer_id, original_name, safe_name, size),
                    )

        except Exception:
            destination.unlink(missing_ok=True)
            raise

        return {
            "status": "success",
            "filename": original_name,
            "stored_as": safe_name,
            "size_bytes": size,
            "duplicate": False,
        }

    finally:
        await file.close()