//! START OF FILE frontend/utils/api.ts
/**
 * API client for NoSlop Backend
 */

// Dynamically determine API URL based on current host
// If accessing via network IP (e.g., 10.0.0.3:3000), use same IP for backend (10.0.0.3:8000)
// Otherwise fall back to localhost for local development
const getApiBaseUrl = () => {
    if (typeof window !== 'undefined') {
        const hostname = window.location.hostname;

        // If on localhost, ALWAYS use localhost:8000
        if (hostname === 'localhost' || hostname === '127.0.0.1') {
            return 'http://localhost:8000';
        }

        // If accessing via network IP (e.g., 192.168.x.x), use same IP for backend
        return `http://${hostname}:8000`;
    }
    // Fall back to environment variable or localhost
    return process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8000';
};

const API_BASE_URL = getApiBaseUrl();

export interface ProjectRequest {
    title: string;
    project_type: string;
    description: string;
    duration?: number;
    style?: string;
    reference_media?: string[];
}

export interface Project {
    id: string;
    title: string;
    project_type: string;
    description: string;
    status: string;
    created_at: string;
    updated_at: string;
    duration?: number;
    style?: string;
    reference_media: string[];
    metadata: Record<string, any>;
}

export interface Task {
    id: string;
    project_id: string;
    task_type: string;
    description: string;
    status: string;
    assigned_to?: string;
    dependencies: string[];
    priority: number;
    progress: number;
    result?: Record<string, any>;
    created_at: string;
    updated_at: string;
}

export interface TaskProgress {
    task_id: string;
    status: string;
    progress: number;
    message?: string;
    result?: Record<string, any>;
}

class ApiClient {
    private baseUrl: string;
    private token: string | null = null;

    constructor(baseUrl: string = API_BASE_URL) {
        this.baseUrl = baseUrl;
    }

    setToken(token: string | null) {
        this.token = token;
    }

    private async request<T>(
        endpoint: string,
        options: RequestInit = {}
    ): Promise<T> {
        const url = `${this.baseUrl}${endpoint}`;
        const response = await fetch(url, {
            ...options,
            headers: {
                'Content-Type': 'application/json',
                ...(this.token ? { 'Authorization': `Bearer ${this.token}` } : {}),
                ...options.headers,
            },
        });

        if (!response.ok) {
            const error = await response.text();
            throw new Error(`API Error: ${response.status} - ${error}`);
        }

        return response.json();
    }

    // Auth APIs
    async login(username: string, password: string): Promise<{ access_token: string; token_type: string }> {
        const formData = new URLSearchParams();
        formData.append('username', username);
        formData.append('password', password);

        const response = await fetch(`${this.baseUrl}/auth/token`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
            },
            body: formData,
        });

        if (!response.ok) {
            const error = await response.text();
            throw new Error(`Login Failed: ${response.status} - ${error}`);
        }

        return response.json();
    }

    async getChatHistory(sessionId: string = 'default'): Promise<{ history: any[] }> {
        return this.request<{ history: any[] }>(`/api/chat/history?session_id=${sessionId}`);
    }

    async getSessions(): Promise<{ sessions: any[] }> {
        return this.request<{ sessions: any[] }>('/api/chat/sessions');
    }

    async createSession(): Promise<any> {
        return this.request('/api/chat/sessions', { method: 'POST' });
    }

    async deleteSession(sessionId: string): Promise<any> {
        return this.request(`/api/chat/sessions/${sessionId}`, { method: 'DELETE' });
    }

    async clearChatHistory(sessionId: string): Promise<any> {
        return this.request(`/api/chat/clear?session_id=${sessionId}`, { method: 'POST' });
    }

    async register(data: any): Promise<any> {
        return this.request('/auth/register', {
            method: 'POST',
            body: JSON.stringify(data),
        });
    }

    // Project APIs
    async createProject(data: ProjectRequest): Promise<Project> {
        return this.request<Project>('/api/projects', {
            method: 'POST',
            body: JSON.stringify(data),
        });
    }

    async getProject(id: string): Promise<Project> {
        return this.request<Project>(`/api/projects/${id}`);
    }

    async listProjects(skip: number = 0, limit: number = 100): Promise<Project[]> {
        const response = await this.request<{ projects: Project[] }>(`/api/projects?skip=${skip}&limit=${limit}`);
        return response.projects;
    }

    async executeProject(id: string): Promise<any> {
        return this.request(`/api/projects/${id}/execute`, {
            method: 'POST',
        });
    }

    // Task APIs
    async getProjectTasks(projectId: string): Promise<Task[]> {
        return this.request<Task[]>(`/api/projects/${projectId}/tasks`);
    }

    async getTaskProgress(taskId: string): Promise<TaskProgress> {
        return this.request<TaskProgress>(`/api/tasks/${taskId}/progress`);
    }

    async executeTask(taskId: string): Promise<any> {
        return this.request(`/api/tasks/${taskId}/execute`, {
            method: 'POST',
        });
    }

    async executeTaskWithDependencies(taskId: string): Promise<any> {
        return this.request(`/api/tasks/${taskId}/execute-with-dependencies`, {
            method: 'POST',
        });
    }

    // Worker APIs
    async listWorkers(): Promise<any[]> {
        return this.request<any[]>('/api/workers');
    }

    async getWorkerCapabilities(workerType: string): Promise<any> {
        return this.request(`/api/workers/${workerType}/capabilities`);
    }

    // Personality APIs
    async getPersonalityPreset(type: string): Promise<any> {
        // This endpoint might not require auth, but it doesn't hurt to send the token if available
        // Need to check if request helper adds token automatically. Yes it does.
        // However, looking at the backend code, `get_personality` endpoint does NOT currently use Depends(get_current_user)
        // But `this.request` handles the full URL construction which fixes the localhost hardcoding issues.
        return this.request<any>(`/api/personality/${type}`);
    }

    async setPersonality(personality: any, sessionId: string = 'default'): Promise<any> {
        return this.request('/api/personality', {
            method: 'POST',
            body: JSON.stringify(personality),
            // The request method automatically adds Content-Type and Authorization header
        }); // Note: The backend endpoint also takes session_id as a query param, but the PersonalitySelector called it with query param.
        // Let's check the backend definition:
        // async def set_personality(personality: PersonalityProfile, session_id: str = "default", ...
        // It expects query param for session_id.
    }

    async setPersonalityWithSession(personality: any, sessionId: string = 'default'): Promise<any> {
        return this.request(`/api/personality?session_id=${sessionId}`, {
            method: 'POST',
            body: JSON.stringify(personality)
        });
    }
}

export const api = new ApiClient();
export default api;
