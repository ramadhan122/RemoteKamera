
from pathlib import Path
from uuid import uuid4
import os

from fastapi import FastAPI, File, Header, HTTPException, UploadFile
from fastapi.responses import HTMLResponse
from fastapi.staticfiles import StaticFiles

BASE_DIR = Path(__file__).resolve().parent.parent
UPLOAD_DIR = BASE_DIR / "received_files"
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)

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

    # Nama internal dibuat acak agar tidak menimpa file yang sudah ada.
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

    except HTTPException:
        destination.unlink(missing_ok=True)
        raise
    except Exception:
        destination.unlink(missing_ok=True)
        raise HTTPException(
            status_code=500,
            detail="Gagal menyimpan file.",
        )
    finally:
        await file.close()

    return {
        "status": "success",
        "filename": original_name,
        "stored_as": safe_name,
        "size_bytes": size,
    }
