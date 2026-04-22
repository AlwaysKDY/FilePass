import hmac
from fastapi import Header, HTTPException


def verify_token(expected_token: str):
    """返回一个 FastAPI 依赖，用于校验 Bearer Token。"""
    async def _verify(authorization: str = Header(...)):
        scheme, _, token = authorization.partition(" ")
        if scheme.lower() != "bearer" or not hmac.compare_digest(token, expected_token):
            raise HTTPException(status_code=401, detail="认证失败")
        return token
    return _verify
