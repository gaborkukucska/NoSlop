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
        // If accessing via IP address (not localhost), use same IP for backend
        if (hostname !== 'localhost' && hostname !== '127.0.0.1') {
            return `http://${hostname}:8000`;
        }
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
}

export const api = new ApiClient();
export default api;
