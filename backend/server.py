import os
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from openai import OpenAI

app = FastAPI()
client = OpenAI(api_key=os.environ.get("OPENAI_API_KEY"))
history = {}  # demo only: replace with persistent per-user storage

class Request(BaseModel):
    conversation_id: str
    message: str
    language: str = "auto"

@app.post("/reply")
def reply(req: Request):
    if not os.environ.get("OPENAI_API_KEY"):
        raise HTTPException(503, "OPENAI_API_KEY missing")
    key=req.conversation_id[:150]
    messages=history.get(key, [])[-12:]
    response=client.responses.create(model="gpt-4.1-mini", instructions="Reply naturally and briefly in the message's language. Do not invent personal facts. Do not send sensitive or financial information.", input=[*messages,{"role":"user","content":req.message}])
    answer=response.output_text.strip()
    history[key]=[*messages,{"role":"user","content":req.message},{"role":"assistant","content":answer}][-12:]
    return {"reply":answer}
