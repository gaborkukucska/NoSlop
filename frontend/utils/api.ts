//! START OF FILE frontend/utils/api.ts
/**
 * API client for NoSlop Backend
 */

// Dynamically determine API URL based on current host
// If accessing via network IP (e.g., 10.0.0.3:3000), use same IP for backend (10.0.0.3:8000)
// Otherwise fall back to localhost for local development
const getApiBaseUrl = () => {
    // 1. If we are on HTTPS, always use relative paths (assuming Caddy/Tunnel handles routing)
    // This prevents Mixed Content errors when accessing via Cloudflare Tunnel
    if (typeof window !== 'undefined' && window.location.protocol === 'https:') {
        return '';
    }

    // 2. Check environment variable (set during build/deployment)
    // This handles local access where we need to point to a specific backend IP
    if (typeof process.env.NEXT_PUBLIC_API_URL !== 'undefined' && process.env.NEXT_PUBLIC_API_URL !== '') {
        return process.env.NEXT_PUBLIC_API_URL;
    }

    // 3. Dynamic detection (fallback for dev mode or unconfigured deployments)
    if (typeof window !== 'undefined') {
        const hostname = window.location.hostname;
        const protocol = window.location.protocol;

        // If on localhost, use localhost backend with proper port
        if (hostname === 'localhost' || hostname === '127.0.0.1') {
            return `${protocol}//localhost:8000`;
        }

        // For HTTP access on valid IP/Host, assume backend is on same IP:8000 
        // if no env var was provided.
        // NOTE: This might fail for worker nodes if they don't have local backend,
        // which is why NEXT_PUBLIC_API_URL should be set in production.
        return `${protocol}//${hostname}:8000`;
    }

    // 4. Last resort fallback
    return 'http://localhost:8000';
};

const getWebSocketUrl = () => {
    // 1. HTTPS check (Priority for Cloudflare Tunnel)
    if (typeof window !== 'undefined' && window.location.protocol === 'https:') {
        // Use relative path upgraded to WSS
        const host = window.location.host;
        return `wss://${host}`;
    }

    // 2. Check API URL environment variable
    if (typeof process.env.NEXT_PUBLIC_API_URL !== 'undefined' && process.env.NEXT_PUBLIC_API_URL !== '') {
        return process.env.NEXT_PUBLIC_API_URL.replace(/^http/, 'ws');
    }

    // 3. Dynamic detection
    if (typeof window !== 'undefined') {
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const hostname = window.location.hostname;

        if (hostname === 'localhost' || hostname === '127.0.0.1') {
            return `${protocol}//localhost:8000`;
        }

        return `${protocol}//${hostname}:8000`;
    }

    return 'ws://localhost:8000';
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

    private onUnauthorized: (() => void) | null = null;

    setUnauthorizedCallback(callback: () => void) {
        this.onUnauthorized = callback;
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

        if (response.status === 401) {
            if (this.onUnauthorized) {
                this.onUnauthorized();
            }
        }

        if (!response.ok) {
            const error = await response.text();
            throw new Error(`API Error: ${response.status} - ${error}`);
        }

        return response.json();
    }

    // Public helper for WebSocket URL
    getWebSocketUrl(): string {
        return getWebSocketUrl();
    }

    // Chat APIs
    async sendMessage(message: string, sessionId: string = 'default'): Promise<{ message: string; suggestions?: string[] }> {
        return this.request<{ message: string; suggestions?: string[] }>(`/api/chat?session_id=${sessionId}`, {
            method: 'POST',
            body: JSON.stringify({ message }),
        });
    }

    async transcribe(audioBlob: Blob): Promise<{ text: string }> {
        const formData = new FormData();
        formData.append('file', audioBlob);

        // We can't use this.request because it sets Content-Type to application/json
        // So we construct the request manually using the same base logic
        const url = `${this.baseUrl}/api/audio/transcribe`;

        const headers: Record<string, string> = {};
        if (this.token) {
            headers['Authorization'] = `Bearer ${this.token}`;
        }

        const response = await fetch(url, {
            method: 'POST',
            headers: headers,
            body: formData
        });

        if (!response.ok) {
            const error = await response.text();
            throw new Error(`Transcription Failed: ${response.status} - ${error}`);
        }

        return response.json();
    }

    async speak(text: string): Promise<Blob> {
        return this.request<any>('/api/audio/speak', { // Returns Blob, handled below
            method: 'POST',
            body: JSON.stringify({ text })
        }).then(async (res: any) => {
            // Wait, this.request returns response.json(). 
            // We need a way to get raw blob.
            // Let's implement a private helper requestRaw if needed, or just do fetch here.

            const url = `${this.baseUrl}/api/audio/speak`;
            const headers: Record<string, string> = {
                'Content-Type': 'application/json'
            };
            if (this.token) {
                headers['Authorization'] = `Bearer ${this.token}`;
            }

            const response = await fetch(url, {
                method: 'POST',
                headers: headers,
                body: JSON.stringify({ text })
            });

            if (!response.ok) {
                const error = await response.text();
                throw new Error(`TTS Failed: ${response.status} - ${error}`);
            }

            return response.blob();
        });
    }

    // Auth APIs
    async login(username: string, password: string): Promise<{ access_token: string; token_type: string; user: any }> {
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
        const response = await this.request<Project[] | { projects: Project[] }>(`/api/projects?skip=${skip}&limit=${limit}`);
        if (Array.isArray(response)) {
            return response;
        }
        return response.projects;
    }

    async executeProject(id: string): Promise<any> {
        return this.request(`/api/projects/${id}/execute`, {
            method: 'POST',
        });
    }

    async startProject(id: string): Promise<any> {
        return this.request(`/api/projects/${id}/start`, {
            method: 'POST',
        });
    }

    async pauseProject(id: string): Promise<any> {
        return this.request(`/api/projects/${id}/pause`, {
            method: 'POST',
        });
    }

    async stopProject(id: string): Promise<any> {
        return this.request(`/api/projects/${id}/stop`, {
            method: 'POST',
        });
    }

    async updateProject(id: string, data: Partial<ProjectRequest>): Promise<Project> {
        return this.request<Project>(`/api/projects/${id}`, {
            method: 'PUT',
            body: JSON.stringify(data),
        });
    }

    async deleteProject(id: string): Promise<any> {
        return this.request(`/api/projects/${id}`, {
            method: 'DELETE',
        });
    }

    // Task APIs
    async getProjectTasks(projectId: string): Promise<Task[]> {
        const response = await this.request<Task[] | { tasks: Task[] }>(`/api/projects/${projectId}/tasks`);
        if (Array.isArray(response)) {
            return response;
        }
        return response.tasks;
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

    // Activity APIs
    async getActivityHistory(): Promise<any[]> {
        return this.request<any[]>('/api/activity/history');
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

    // Setup & Priming APIs
    async getSetupStatus(): Promise<{ setup_required: boolean; username: string }> {
        return this.request<{ setup_required: boolean; username: string }>('/api/setup/status');
    }

    async completeSetup(data: any): Promise<any> {
        return this.request('/api/setup/complete', {
            method: 'POST',
            body: JSON.stringify(data)
        });
    }

    async primeAdminAI(sessionId: string = 'default'): Promise<any> {
        return this.request(`/api/admin/prime?session_id=${sessionId}`, {
            method: 'POST'
        });
    }

    // Admin APIs
    async getUsers(skip: number = 0, limit: number = 100): Promise<{ users: any[] }> {
        return this.request<{ users: any[] }>(`/api/admin/users?skip=${skip}&limit=${limit}`);
    }

    async adminUpdateUser(userId: string, updates: any): Promise<any> {
        return this.request(`/api/admin/users/${userId}`, {
            method: 'PUT',
            body: JSON.stringify(updates),
        });
    }

    async adminDeleteUser(userId: string): Promise<any> {
        return this.request(`/api/admin/users/${userId}`, {
            method: 'DELETE',
        });
    }

    async getSystemSettings(): Promise<any> {
        return this.request('/api/admin/settings');
    }

    async updateSystemSettings(settings: any): Promise<any> {
        return this.request('/api/admin/settings', {
            method: 'PUT',
            body: JSON.stringify(settings),
        });
    }

    async exportData(): Promise<any> {
        return this.request('/api/admin/export');
    }

    async importData(data: any): Promise<any> {
        return this.request('/api/admin/import', {
            method: 'POST',
            body: JSON.stringify(data),
        });
    }
}

export const api = new ApiClient();
export default api;
