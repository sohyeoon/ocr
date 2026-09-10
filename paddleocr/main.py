"""
main.py

FastAPI 실행
"""

from fastapi.middleware.cors import CORSMiddleware

import os

# oneDNN 최적화를 사용하지 않는다. (PaddPaddle 내부 옵션)
os.environ["FLAGS_use_onednn"] = "0"

from fastapi import FastAPI, UploadFile, File
from fastapi.responses import JSONResponse

from pathlib import Path
import shutil
import uuid
import traceback

from config import (
    INPUT_DIR
)

from ocr_service import OCRService
from olePageno import OlePageLocator


app = FastAPI(
    title="PaddleOCR API"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


ocr_service = OCRService()
ole_locator = OlePageLocator()


@app.post("/ocr")
async def ocr(file: UploadFile = File(...)):

    try:

        # 임시 파일 저장
        file_id = str(uuid.uuid4())

        file_path = INPUT_DIR / (
            file_id + Path(file.filename).suffix
        )
        print(f"[FILE] file_path = {file_path}")

        with open(file_path, "wb") as buffer:
            shutil.copyfileobj(
                file.file,
                buffer
            )
        result = ocr_service.process_file(
                file_path
            )


        # 임시 파일 삭제
        file_path.unlink(
            missing_ok=True
        )


        return JSONResponse(
            content=result
        )


    except Exception as e:

        traceback.print_exc()

        return JSONResponse(
            status_code=500,
            content={
                "success": False,
                "message": str(e)
            }
        )

@app.post("/ole-pagenumber")
async def ole_pagenumber(file: UploadFile = File(...)):
    try:
        file_id = str(uuid.uuid4())
        file_path = INPUT_DIR / (file_id + Path(file.filename).suffix)

        with open(file_path, "wb") as buffer:
            shutil.copyfileobj(file.file, buffer)

        result = ole_locator.get_ole_page_numbers(str(file_path))

        file_path.unlink(missing_ok=True)

        return JSONResponse(
            content={"success": True, "data": result}
        )

    except Exception as e:
        traceback.print_exc()
        return JSONResponse(
            status_code=500,
            content={
                "success": False,
                "message": str(e)
            }
        )

