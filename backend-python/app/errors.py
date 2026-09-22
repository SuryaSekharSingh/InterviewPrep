from __future__ import annotations

import uuid

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def api_error(_: Request, error: ApiError):
        return JSONResponse(
            status_code=error.status,
            content={
                "code": error.code,
                "message": error.message,
                "requestId": str(uuid.uuid4()),
            },
        )

    @app.exception_handler(Exception)
    async def unexpected(_: Request, error: Exception):
        # Keep student data and implementation details out of API errors.
        return JSONResponse(
            status_code=500,
            content={
                "code": "INTERNAL_ERROR",
                "message": "The request could not be completed.",
            },
        )
