from fastapi import FastAPI
import ollama
import uvicorn

app = FastAPI()

@app.get("/")
def read_root():
    return {"Hello": "NoSlop Backend"}

@app.get("/health")
def health_check():
    """
    Checks the health of the backend and the connection to Ollama.
    """
    try:
        # Attempt to list models to verify connection
        # This assumes the ollama python client is configured to look at localhost:11434 by default
        # or that the OLLAMA_HOST env var is set if different.
        models = ollama.list()
        return {
            "status": "ok", 
            "ollama": "connected", 
            "model_count": len(models.get('models', []))
        }
    except Exception as e:
        return {
            "status": "degraded", 
            "ollama": "disconnected", 
            "error": str(e)
        }

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
