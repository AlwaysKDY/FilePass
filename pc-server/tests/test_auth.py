"""测试 auth.py"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import pytest
from fastapi import FastAPI, Depends, HTTPException
from httpx import AsyncClient, ASGITransport
from auth import verify_token


pytestmark = pytest.mark.asyncio

TOKEN = "secret-token-xyz"


@pytest.fixture()
def auth_app():
    app = FastAPI()
    dep = verify_token(TOKEN)

    @app.get("/protected")
    async def protected(_t: str = Depends(dep)):
        return {"ok": True}

    return app


@pytest.fixture()
async def auth_client(auth_app):
    transport = ASGITransport(app=auth_app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


async def test_valid_token(auth_client):
    resp = await auth_client.get("/protected", headers={"Authorization": f"Bearer {TOKEN}"})
    assert resp.status_code == 200


async def test_invalid_token(auth_client):
    resp = await auth_client.get("/protected", headers={"Authorization": "Bearer wrong"})
    assert resp.status_code == 401


async def test_missing_header(auth_client):
    resp = await auth_client.get("/protected")
    assert resp.status_code == 422  # FastAPI validation error


async def test_bad_scheme(auth_client):
    resp = await auth_client.get("/protected", headers={"Authorization": f"Basic {TOKEN}"})
    assert resp.status_code == 401
